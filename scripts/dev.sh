#!/usr/bin/env bash
# Mirboard 로컬 실행 wrapper (Phase 14 — D-68).
#
# Fly.io 배포(flyctl deploy) 와 별개로, 로컬에서 인프라+서버+클라를 손쉽게
# 올리기 위한 단축 명령. 새 런타임 코드 없음 — docker compose / gradle / npm
# 을 얇게 감싼다. JWT 등 dev env 는 application.yml 기본값이 내장돼 있어
# 별도 export 불필요 (운영만 secret 주입).
#
# 사용법: ./scripts/dev.sh <subcommand> [args]
# 자세히는 ./scripts/dev.sh --help

set -e

# ── repo root 로 이동 (어디서 실행해도 동작) ──
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
    echo "ERROR: not in a git repository" >&2
    exit 1
fi
cd "$REPO_ROOT"

# ── Colima Docker socket 자동 감지 (docker compose CLI 일관성용) ──
if [ -z "$DOCKER_HOST" ] && [ -S "$HOME/.colima/default/docker.sock" ]; then
    export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
fi

# ── ANSI 색상 (TTY 일 때만) ──
if [ -t 1 ]; then
    BOLD="\033[1m"; DIM="\033[2m"; RED="\033[31m"; RESET="\033[0m"
else
    BOLD=""; DIM=""; RED=""; RESET=""
fi

log() {
    printf "${BOLD}──[dev:%s]──${RESET} %s\n" "$SUBCMD" "$1"
}

usage() {
    cat <<'EOF'
Mirboard 로컬 실행 wrapper

Usage: ./scripts/dev.sh <subcommand> [args]

  up                인프라 기동 — docker compose postgres+redis + Flyway
                    마이그레이션(V1~V3 + 시드 봇). 멱등.
  server            up 보장 후 서버 기동 (./gradlew :server:bootRun, :8080)
  client            클라 dev 기동 (Vite :5173, /api·/ws → :8080 proxy)
  bundled           React 번들 동봉 단일 프로세스 (:8080 만으로 prod 유사 확인)
  all               up + 서버(백그라운드 로그) + 클라(포그라운드). Ctrl-C 정리.
  down [--purge]    컨테이너 중지 (데이터 보존). --purge 면 볼륨까지 삭제.
  -h, --help        본 메시지

dev env(JWT/DB/Redis) 는 application.yml 기본값 내장 — 추가 export 불필요.
검증/테스트는 ./scripts/check.sh, 실행은 ./scripts/dev.sh.

예시:
  ./scripts/dev.sh up && ./scripts/dev.sh all   # 한 방에 로컬 풀스택
  ./scripts/dev.sh bundled                      # localhost:8080 단독 확인
  ./scripts/dev.sh down --purge                 # DB 초기화
EOF
}

# postgres/redis 가 healthy 해질 때까지 폴링 (최대 ~40s).
wait_infra() {
    log "postgres / redis 헬스 대기"
    local tries=0
    until docker compose exec -T postgres pg_isready -U mirboard -d mirboard >/dev/null 2>&1 \
        && docker compose exec -T redis redis-cli PING >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -gt 40 ]; then
            echo "${RED}ERROR: 인프라가 40초 내 healthy 되지 않음${RESET}" >&2
            docker compose ps >&2
            exit 1
        fi
        sleep 1
    done
}

# 호스트 포트를 mirboard 가 아닌 컨테이너가 점유 중이면 명확히 안내하고 중단.
check_port_conflict() {
    local port="$1"
    local owner
    owner="$(docker ps --filter "publish=${port}" --format '{{.Names}}' 2>/dev/null \
        | grep -v '^mirboard-' | head -1 || true)"
    if [ -n "$owner" ]; then
        echo "${RED}ERROR: 호스트 포트 ${port} 를 다른 컨테이너 '${owner}' 가 점유 중.${RESET}" >&2
        echo "  → 그 컨테이너를 끄거나, docker-compose.override.yml 로 mirboard 로컬" >&2
        echo "    포트를 바꾸세요 (예: 5442/6389). README '로컬 실행' 참고." >&2
        exit 1
    fi
}

infra_up() {
    check_port_conflict 5432
    check_port_conflict 6379
    log "docker compose up -d postgres redis"
    docker compose up -d postgres redis
    wait_infra
    log "Flyway 마이그레이션 (V1~V3 + 시드 봇)"
    docker compose --profile migrate run --rm flyway
    log "인프라 준비 완료"
}

ensure_infra() {
    # postgres 컨테이너가 떠 있고 healthy 면 재기동 skip.
    if docker compose exec -T postgres pg_isready -U mirboard -d mirboard >/dev/null 2>&1 \
        && docker compose exec -T redis redis-cli PING >/dev/null 2>&1; then
        log "인프라 이미 healthy — skip"
    else
        infra_up
    fi
}

wait_server() {
    log "서버 부팅 대기 (http://localhost:8080/actuator/health)"
    local tries=0
    until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -gt 90 ]; then
            echo "${RED}ERROR: 서버가 90초 내 UP 되지 않음 (build/dev-server.log 확인)${RESET}" >&2
            exit 1
        fi
        sleep 1
    done
    log "서버 UP"
}

SUBCMD="${1:-}"
case "$SUBCMD" in
    up)
        infra_up
        ;;

    server)
        ensure_infra
        log "./gradlew :server:bootRun (:8080) — Ctrl-C 로 종료"
        ./gradlew :server:bootRun
        ;;

    client)
        if [ ! -d "client/node_modules" ]; then
            log "client/node_modules 없음 → npm --prefix client install"
            npm --prefix client install
        fi
        log "Vite dev (:5173, /api·/ws → :8080) — Ctrl-C 로 종료"
        npm --prefix client run dev
        ;;

    bundled)
        ensure_infra
        log "./gradlew :server:bootRun -PbundleClient (:8080 단독) — Ctrl-C 로 종료"
        ./gradlew :server:bootRun -PbundleClient
        ;;

    all)
        ensure_infra
        if [ ! -d "client/node_modules" ]; then
            log "client/node_modules 없음 → npm --prefix client install"
            npm --prefix client install
        fi
        mkdir -p build
        log "서버 백그라운드 기동 → 로그: build/dev-server.log"
        ./gradlew :server:bootRun > build/dev-server.log 2>&1 &
        SERVER_PID=$!
        # 스크립트 종료 시 백그라운드 서버 정리.
        trap 'echo; log "정리: 서버(PID '"$SERVER_PID"') 종료"; kill "$SERVER_PID" 2>/dev/null || true' INT TERM EXIT
        wait_server
        log "Vite dev (:5173) 포그라운드 — Ctrl-C 시 서버까지 정리"
        npm --prefix client run dev
        ;;

    down)
        if [ "${2:-}" = "--purge" ]; then
            log "docker compose down -v (볼륨/데이터 삭제)"
            docker compose --profile migrate down -v
        else
            log "docker compose stop postgres redis (데이터 보존)"
            docker compose stop postgres redis
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
