#!/usr/bin/env bash
# Mirboard 검증 wrapper (Phase 11 — D-60).
#
# 자주 쓰는 검증 명령 7 개를 단축. OrbStack/Colima Docker socket 자동 감지로
# 매번 DOCKER_HOST/TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE prefix 입력 불필요.
#
# 사용법: ./scripts/check.sh <subcommand> [args]
# 자세히는 ./scripts/check.sh --help

set -e

# ── repo root 로 이동 (어디서 실행해도 동작) ──
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
    echo "ERROR: not in a git repository" >&2
    exit 1
fi
cd "$REPO_ROOT"

# ── Docker socket 자동 감지 (OrbStack / Colima) ──
# DOCKER_HOST 가 이미 셋이면 존중. 없으면 OrbStack → Colima 순으로 시도 (D-72).
# 둘 다 in-container 경로는 /var/run/docker.sock 로 매핑되므로 TC override 동일.
# CI Ubuntu runner 에선 socket 부재 → export 생략.
if [ -z "$DOCKER_HOST" ]; then
    if [ -S "$HOME/.orbstack/run/docker.sock" ]; then
        export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
        export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
    elif [ -S "$HOME/.colima/default/docker.sock" ]; then
        export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
        export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
    fi
fi

# ── ANSI 색상 (TTY 일 때만) ──
if [ -t 1 ]; then
    BOLD="\033[1m"; DIM="\033[2m"; GREEN="\033[32m"; RED="\033[31m"; RESET="\033[0m"
else
    BOLD=""; DIM=""; GREEN=""; RED=""; RESET=""
fi

log() {
    printf "${BOLD}──[check:%s]──${RESET} %s\n" "$SUBCMD" "$1"
}

usage() {
    cat <<'EOF'
Mirboard 검증 wrapper

Usage: ./scripts/check.sh <subcommand> [args]

  fast              빠른 회귀 (클라 tsc+vitest + 서버 compile, ~30s)
                    pre-commit hook 과 동일 로직.
  rules             서버 룰 도메인 단위 (티츄 + 스컬킹 전량, ~5s)
                    Docker 불필요.
  server            서버 풀 (단위 + IT, Docker 필요, ~1m20s)
  client            클라 풀 (build:check + test + build, ~10s)
  all               server + client (~1m30s)
  bot-stress [N]    BotMatchSimulationIT N회 시뮬레이션 (기본 10, ~5s/매치)
  infra             docker compose ps + Postgres/Redis 헬스 (~2s)
  load [VUs]        k6 REST 스모크 부하 (기본 5 VU, ~35s). 서버가 :8080 에 떠 있어야 함.
                    k6 미설치면 Docker 이미지로 폴백 (M2-C5, D-92).
                    인게임 부하는 bot-stress 담당 — 역할 분담은 scripts/k6/README.md.
  backup ...        백업/복구는 ./scripts/backup.sh 로 위임 (dump/restore/list/verify/prune)
  -h, --help        본 메시지

OrbStack/Colima Docker socket 자동 감지 — DOCKER_HOST 수동 설정 불필요.

예시:
  ./scripts/check.sh fast              # 커밋 전 빠른 확인
  ./scripts/check.sh rules             # 룰 변경 후 단위 회귀
  ./scripts/check.sh bot-stress 50     # 봇 50 매치 stress
  ./scripts/check.sh all               # PR 전 풀 회귀
EOF
}

# ── 서브커맨드 dispatch ──
SUBCMD="${1:-}"
case "$SUBCMD" in
    fast)
        log "클라 tsc + vitest + 서버 compile"
        if [ -d "client/node_modules" ]; then
            npm --prefix client run build:check
            npm --prefix client run test
        else
            echo "  (skip 클라 — node_modules 없음. 'npm --prefix client install' 후 재시도)"
        fi
        ./gradlew :server:compileJava :server:compileTestJava -q
        log "모두 통과"
        ;;

    rules)
        log "서버 룰 도메인 단위 테스트 (Docker 불필요)"
        ./gradlew :server:test \
            --tests "com.mirboard.domain.game.tichu.card.*" \
            --tests "com.mirboard.domain.game.tichu.hand.*" \
            --tests "com.mirboard.domain.game.tichu.scoring.*" \
            --tests "com.mirboard.domain.game.tichu.action.*" \
            --tests "com.mirboard.domain.game.tichu.invariant.*" \
            --tests "com.mirboard.domain.game.tichu.TichuEngineRoundSimulationTest" \
            --tests "com.mirboard.domain.game.tichu.TichuSpecialCardScenarioTest" \
            --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest" \
            --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest" \
            --tests "com.mirboard.domain.game.skullking.*"
        log "모두 통과"
        ;;

    server)
        log "서버 풀 테스트 (단위 + IT)"
        if [ -n "$DOCKER_HOST" ]; then
            log "${DIM}DOCKER_HOST=$DOCKER_HOST${RESET}"
        fi
        ./gradlew :server:test
        log "모두 통과"
        ;;

    client)
        log "클라 풀 (build:check + test + build)"
        if [ ! -d "client/node_modules" ]; then
            log "node_modules 없음 → npm ci"
            npm --prefix client ci
        fi
        npm --prefix client run build:check
        npm --prefix client run test
        npm --prefix client run build
        log "모두 통과"
        ;;

    all)
        SUBCMD="all (server)" "$0" server
        SUBCMD="all (client)" "$0" client
        ;;

    bot-stress)
        COUNT="${2:-10}"
        if ! [[ "$COUNT" =~ ^[0-9]+$ ]]; then
            echo "ERROR: bot-stress 인자는 양의 정수여야 합니다 (받은 값: '$COUNT')" >&2
            exit 1
        fi
        log "BotMatchSimulationIT 시뮬레이션 ${COUNT}회"
        ./gradlew :server:test \
            --tests "com.mirboard.infra.bot.BotMatchSimulationIT" \
            -Dmirboard.bot.simulation-count="$COUNT" \
            --rerun-tasks
        log "${COUNT} 매치 모두 통과"
        ;;

    infra)
        log "docker compose status + 헬스"
        docker compose ps 2>&1 | head -10
        echo ""
        log "Postgres SELECT 1"
        docker compose exec -T postgres psql -U mirboard -d mirboard -tc "SELECT 1" 2>&1 || \
            { echo "${RED}Postgres unhealthy${RESET}" >&2; exit 1; }
        log "Redis PING"
        docker compose exec -T redis redis-cli PING 2>&1 || \
            { echo "${RED}Redis unhealthy${RESET}" >&2; exit 1; }
        log "infra 정상"
        ;;

    load)
        VUS="${2:-5}"
        if ! [[ "$VUS" =~ ^[0-9]+$ ]]; then
            echo "ERROR: load 인자는 양의 정수여야 합니다 (받은 값: '$VUS')" >&2
            exit 1
        fi
        BASE_URL="${MIRBOARD_BASE_URL:-http://localhost:8080}"

        # 서버가 안 떠 있으면 k6 가 전부 실패로 도배되므로 먼저 확인한다.
        # 인증 필요 엔드포인트라 401 이 정상 응답.
        CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/games" 2>/dev/null || echo "000")
        if [ "$CODE" != "401" ] && [ "$CODE" != "200" ]; then
            echo "${RED}ERROR: 서버 응답 없음 ($BASE_URL → $CODE)${RESET}" >&2
            echo "  './scripts/dev.sh server' 로 먼저 띄우세요." >&2
            exit 1
        fi

        # 재실행 시 계정 충돌을 피하려고 실행마다 태그를 바꾼다(409 는 허용이지만
        # 매번 새 계정이 부하 특성에 더 가깝다).
        TAG="$(date +%H%M%S)"
        if command -v k6 >/dev/null 2>&1; then
            log "k6 (로컬) — ${VUS} VU, base=$BASE_URL"
            MIRBOARD_BASE_URL="$BASE_URL" MIRBOARD_LOAD_VUS="$VUS" K6_RUN_TAG="$TAG" \
                k6 run scripts/k6/smoke.js
        else
            log "k6 미설치 → Docker 이미지로 실행 (${VUS} VU)"
            # 컨테이너에서 호스트 서버를 보려면 localhost 를 host.docker.internal 로.
            DOCKER_BASE="${BASE_URL/localhost/host.docker.internal}"
            DOCKER_BASE="${DOCKER_BASE/127.0.0.1/host.docker.internal}"
            docker run --rm -i --add-host=host.docker.internal:host-gateway \
                -e MIRBOARD_BASE_URL="$DOCKER_BASE" \
                -e MIRBOARD_LOAD_VUS="$VUS" \
                -e K6_RUN_TAG="$TAG" \
                -v "$REPO_ROOT/scripts/k6:/scripts:ro" \
                grafana/k6:latest run /scripts/smoke.js
        fi
        log "부하 시나리오 통과 (임계값 위반 시 k6 가 비정상 종료)"
        ;;

    backup)
        log "백업/복구는 전용 스크립트로 위임합니다"
        echo ""
        exec "$REPO_ROOT/scripts/backup.sh" "${@:2}"
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
