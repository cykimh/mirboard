#!/usr/bin/env bash
# Mirboard 검증 wrapper (Phase 11 — D-60).
#
# 자주 쓰는 검증 명령 7 개를 단축. Colima Docker socket 자동 감지로 매번
# DOCKER_HOST/TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE prefix 입력 불필요.
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

# ── Colima Docker socket 자동 감지 ──
# DOCKER_HOST 가 이미 셋이면 존중. 없으면 ~/.colima/default/docker.sock 시도.
# CI Ubuntu runner 에선 socket 부재 → export 생략.
if [ -z "$DOCKER_HOST" ] && [ -S "$HOME/.colima/default/docker.sock" ]; then
    export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
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
  rules             서버 룰 도메인 단위 (card/hand/scoring/action/invariant, ~3s)
                    Docker 불필요.
  server            서버 풀 (단위 + IT, Docker 필요, ~1m20s)
  client            클라 풀 (build:check + test + build, ~10s)
  all               server + client (~1m30s)
  bot-stress [N]    BotMatchSimulationIT N회 시뮬레이션 (기본 10, ~5s/매치)
  infra             docker compose ps + Postgres/Redis 헬스 (~2s)
  -h, --help        본 메시지

Colima Docker socket 자동 감지 — DOCKER_HOST 수동 설정 불필요.

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
            --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest"
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
