#!/usr/bin/env bash
# Mirboard 백업/복구 wrapper (M2-C5 — D-92).
#
# 대상은 **Postgres 뿐**이다. Redis 는 설계상 휘발(방/게임 상태·칩·세션 전부 TTL
# 6h 이하)이라 복구 대상이 아니다 — 자세한 배경과 복구 불가 범위는
# docs/runbooks/backup-restore.md 를 읽을 것.
#
# 사용법: ./scripts/backup.sh <subcommand> [args]
# 자세히는 ./scripts/backup.sh --help

set -euo pipefail

# ── repo root 로 이동 (어디서 실행해도 동작) ──
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
    echo "ERROR: not in a git repository" >&2
    exit 1
fi
cd "$REPO_ROOT"

# ── Docker socket 자동 감지 (OrbStack → Colima, D-72 와 동일 순서) ──
if [ -z "${DOCKER_HOST:-}" ]; then
    if [ -S "$HOME/.orbstack/run/docker.sock" ]; then
        export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
    elif [ -S "$HOME/.colima/default/docker.sock" ]; then
        export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
    fi
fi

PG_USER="${POSTGRES_USER:-mirboard}"
PG_DB="${POSTGRES_DB:-mirboard}"
BACKUP_DIR="${MIRBOARD_BACKUP_DIR:-$REPO_ROOT/backups}"

if [ -t 1 ]; then
    BOLD="\033[1m"; DIM="\033[2m"; RED="\033[31m"; YELLOW="\033[33m"; RESET="\033[0m"
else
    BOLD=""; DIM=""; RED=""; YELLOW=""; RESET=""
fi

log()  { printf "${BOLD}──[backup:%s]──${RESET} %s\n" "$SUBCMD" "$1"; }
warn() { printf "${YELLOW}%s${RESET}\n" "$1" >&2; }
die()  { printf "${RED}ERROR: %s${RESET}\n" "$1" >&2; exit 1; }

require_postgres() {
    docker compose exec -T postgres pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 \
        || die "Postgres 가 준비되지 않았습니다. './scripts/dev.sh up' 먼저 실행하세요."
}

usage() {
    cat <<'EOF'
Mirboard 백업/복구 wrapper (Postgres 전용 — Redis 는 휘발이라 대상 아님)

Usage: ./scripts/backup.sh <subcommand> [args]

  dump [라벨]        Postgres 논리 백업 생성 (custom format, gzip 내장).
                     파일: backups/mirboard-<UTC타임스탬프>[-라벨].dump
  restore <파일>     백업으로 복구. **기존 데이터를 덮어쓴다** — 확인 프롬프트 있음.
                     MIRBOARD_BACKUP_YES=1 이면 프롬프트 생략(CI/자동화).
  list               backups/ 의 백업 목록 (크기·시각)
  verify <파일>      복구하지 않고 백업 무결성만 확인 (pg_restore --list)
  prune [N]          최근 N개(기본 7)만 남기고 삭제
  -h, --help         본 메시지

환경변수:
  MIRBOARD_BACKUP_DIR   백업 디렉토리 (기본 <repo>/backups)
  MIRBOARD_BACKUP_YES   restore 확인 프롬프트 생략
  POSTGRES_USER / POSTGRES_DB   (기본 mirboard / mirboard)

예시:
  ./scripts/backup.sh dump before-v9      # 마이그레이션 전 스냅샷
  ./scripts/backup.sh list
  ./scripts/backup.sh verify backups/mirboard-20260730T....dump
  ./scripts/backup.sh restore backups/mirboard-20260730T....dump
EOF
}

SUBCMD="${1:-}"
case "$SUBCMD" in
    dump)
        require_postgres
        mkdir -p "$BACKUP_DIR"
        LABEL="${2:-}"
        STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
        NAME="mirboard-${STAMP}${LABEL:+-$LABEL}.dump"
        OUT="$BACKUP_DIR/$NAME"

        # custom format(-Fc): 압축 내장 + pg_restore 로 선택적 복구 가능.
        # 컨테이너 **안에서** 파일로 뽑고 거기서 검증한 뒤 호스트로 꺼낸다.
        # (stdout 파이프로 받으면 pg_restore --list 가 seek 을 못 해 검증이 불가능하다.)
        TMP="/tmp/$NAME"
        log "pg_dump → $NAME"
        docker compose exec -T postgres \
            pg_dump -U "$PG_USER" -d "$PG_DB" -Fc -f "$TMP" \
            || die "pg_dump 실패"

        log "무결성 확인 (pg_restore --list)"
        if ! docker compose exec -T postgres pg_restore --list "$TMP" >/dev/null 2>&1; then
            docker compose exec -T postgres rm -f "$TMP" || true
            die "덤프가 손상됐습니다 (pg_restore --list 실패)"
        fi

        docker compose cp "postgres:$TMP" "$OUT" >/dev/null \
            || { docker compose exec -T postgres rm -f "$TMP" || true; die "호스트로 복사 실패"; }
        docker compose exec -T postgres rm -f "$TMP" || true

        # 빈 파일이 조용히 "백업 성공"으로 남는 것을 막는다.
        SIZE=$(wc -c < "$OUT" | tr -d ' ')
        [ "$SIZE" -gt 0 ] || { rm -f "$OUT"; die "덤프가 비어 있습니다"; }

        log "완료 — $OUT ($(du -h "$OUT" | cut -f1))"
        ;;

    restore)
        FILE="${2:-}"
        [ -n "$FILE" ] || die "복구할 백업 파일을 지정하세요. './scripts/backup.sh list' 참고"
        [ -f "$FILE" ] || die "파일이 없습니다: $FILE"
        require_postgres

        log "대상 DB: $PG_DB (컨테이너 mirboard-postgres)"
        warn "이 작업은 $PG_DB 의 기존 데이터를 덮어씁니다."
        if [ "${MIRBOARD_BACKUP_YES:-}" != "1" ]; then
            printf "계속하려면 '%s' 를 입력하세요: " "$PG_DB"
            read -r CONFIRM
            [ "$CONFIRM" = "$PG_DB" ] || die "취소됨"
        fi

        # --clean --if-exists: 기존 객체 drop 후 재생성. 단일 트랜잭션은 쓰지 않는다 —
        # drop 대상이 없을 때의 무해한 에러로 전체가 롤백되면 복구가 오히려 어려워진다.
        # dump 와 같은 이유로 컨테이너 안에 넣고 파일에서 읽는다(seek 필요).
        TMP="/tmp/restore-$(basename "$FILE")"
        docker compose cp "$FILE" "postgres:$TMP" >/dev/null || die "컨테이너로 복사 실패"

        log "pg_restore 실행"
        docker compose exec -T postgres \
            pg_restore -U "$PG_USER" -d "$PG_DB" --clean --if-exists --no-owner "$TMP" \
            || warn "pg_restore 가 경고와 함께 종료했습니다 (drop 대상 부재는 정상)."
        docker compose exec -T postgres rm -f "$TMP" || true

        log "복구 후 행 수"
        docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -tc "
            SELECT 'users=' || count(*) FROM users
            UNION ALL SELECT 'matches=' || count(*) FROM tichu_match_results
            UNION ALL SELECT 'participants=' || count(*) FROM tichu_match_participants
            UNION ALL SELECT 'flyway=' || count(*) FROM flyway_schema_history;"

        warn "서버가 떠 있었다면 재시작하세요 — 커넥션 풀이 옛 스냅샷을 잡고 있을 수 있습니다."
        log "완료"
        ;;

    verify)
        FILE="${2:-}"
        [ -n "$FILE" ] || die "확인할 백업 파일을 지정하세요"
        [ -f "$FILE" ] || die "파일이 없습니다: $FILE"
        require_postgres
        TMP="/tmp/verify-$(basename "$FILE")"
        docker compose cp "$FILE" "postgres:$TMP" >/dev/null || die "컨테이너로 복사 실패"
        log "pg_restore --list (복구하지 않음)"
        docker compose exec -T postgres pg_restore --list "$TMP" \
            | grep -E "TABLE DATA|SEQUENCE" | head -20 \
            || { docker compose exec -T postgres rm -f "$TMP" || true; die "무결성 확인 실패"; }
        docker compose exec -T postgres rm -f "$TMP" || true
        log "무결성 OK"
        ;;

    list)
        if [ ! -d "$BACKUP_DIR" ] || [ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]; then
            log "백업 없음 ($BACKUP_DIR)"
            exit 0
        fi
        log "$BACKUP_DIR"
        ls -lh "$BACKUP_DIR"/*.dump 2>/dev/null | awk '{print "  " $9 "  " $5 "  " $6, $7, $8}'
        ;;

    prune)
        KEEP="${2:-7}"
        [[ "$KEEP" =~ ^[0-9]+$ ]] || die "prune 인자는 양의 정수여야 합니다 (받은 값: '$KEEP')"
        [ -d "$BACKUP_DIR" ] || { log "백업 디렉토리 없음"; exit 0; }
        # 최신 KEEP 개를 제외한 나머지를 지운다. 파일명이 UTC 타임스탬프라 사전순=시간순.
        # mapfile 은 macOS 기본 bash 3.2 에 없으므로 while-read 로 (이식성).
        DELETED=0
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            log "삭제: $(basename "$f")"
            rm -f "$f"
            DELETED=$((DELETED + 1))
        done < <(ls -1 "$BACKUP_DIR"/*.dump 2>/dev/null | sort -r | tail -n +$((KEEP + 1)))
        if [ "$DELETED" -eq 0 ]; then
            log "삭제 대상 없음 (보관 ${KEEP}개 이하)"
        else
            log "${DELETED}개 삭제, 최근 ${KEEP}개 보관"
        fi
        ;;

    -h|--help|help)
        usage
        ;;

    "")
        usage
        exit 1
        ;;

    *)
        echo "ERROR: 알 수 없는 서브커맨드 '$SUBCMD'" >&2
        echo ""
        usage
        exit 1
        ;;
esac
