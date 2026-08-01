# 기여 가이드

Mirboard 를 로컬에서 돌리고 검증하는 방법. 프로젝트가 무엇인지는 [README](README.md),
설계 배경은 [docs/architecture.md](docs/architecture.md) 를 먼저 보세요.

> 검증 환경: macOS (Apple Silicon). Gradle wrapper(`gradlew`)와 `build.gradle.kts` 는
> 리포에 포함돼 있습니다.

### 1. JDK 25 LTS 설치

```bash
brew install --cask corretto@25       # 또는 SDKMAN, foojay 등
# 확인
/usr/libexec/java_home -V             # corretto-25.0.3 가 보여야 함
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
```

### 2. 컨테이너 런타임 (OrbStack 권장)

**OrbStack (권장):**
```bash
brew install --cask orbstack
open -a OrbStack                       # 최초 1회 기동
docker version                         # Client + Server 둘 다 OK 출력
```

**Colima (대안):**
```bash
brew install colima docker docker-compose
mkdir -p ~/.docker && cat > ~/.docker/config.json <<'EOF'
{
  "cliPluginsExtraDirs": [
    "/opt/homebrew/lib/docker/cli-plugins"
  ]
}
EOF
colima start --cpu 2 --memory 4 --disk 20
docker version                         # Client + Server 둘 다 OK 출력
```

`scripts/dev.sh` · `scripts/check.sh` 는 `DOCKER_HOST` 미설정 시
OrbStack(`~/.orbstack/run/docker.sock`) → Colima(`~/.colima/default/docker.sock`)
순으로 소켓을 자동 감지한다 (D-72). Docker Desktop 은 기본 context 로 그대로 동작.
둘 다 떠 있으면 OrbStack 이 우선이며, Colima 를 강제하려면
`export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"`.

### 3. 인프라 + 마이그레이션

```bash
cd /path/to/mirboard
docker compose up -d postgres redis    # Postgres 16 + Redis 7 기동
docker compose --profile migrate run --rm flyway  # V1__init.sql 적용
```

### 4. 환경변수

JWT 시크릿은 32바이트 이상 필요:

```bash
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"
```

### 5. (옵션) Gradle wrapper 재생성

리포에 `gradlew` 가 이미 있지만 손상 시:

```bash
gradle wrapper --gradle-version 9.4.1   # 또는 기존 wrapper 사용
```

JDK 25 가 로컬에 없으면 Gradle 의 foojay 리졸버가 자동으로 받아온다 (인터넷 필요).

## 서버 빌드 / 실행

---

## 서버 빌드 / 실행

```bash
# (필수) 인프라가 떠 있어야 함
docker compose up -d postgres redis

# (필수) JWT 시크릿
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"

# 서버 기동 (port 8080, dev 환경)
./gradlew :server:bootRun

# 별도 터미널 — 클라이언트 dev (port 5173, /api & /ws 는 8080 으로 proxy)
npm --prefix client install     # 처음 한 번
npm --prefix client run dev
```

브라우저로 http://localhost:5173 접속 → 회원가입 → 4 탭 띄워서 4명 모이면 자동 시작.

### 테스트

```bash
# 단위 테스트 (Docker 불필요, 빠름)
./gradlew :server:test \
  --tests "com.mirboard.domain.game.tichu.card.*" \
  --tests "com.mirboard.domain.game.tichu.hand.*" \
  --tests "com.mirboard.domain.game.tichu.scoring.*" \
  --tests "com.mirboard.domain.game.tichu.action.*" \
  --tests "com.mirboard.domain.game.tichu.TichuEngineRoundSimulationTest" \
  --tests "com.mirboard.domain.game.tichu.DealingLifecycleTest" \
  --tests "com.mirboard.domain.game.tichu.persistence.TichuMatchStateTest" \
  --tests "com.mirboard.domain.lobby.auth.*"

# 통합 테스트 (Testcontainers). scripts/check.sh 가 Colima/OrbStack socket 을
# 자동 감지하므로 wrapper 사용 시 아래 export 불필요. raw gradlew 직접 호출 시만:
export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"   # Colima: ~/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
./gradlew :server:test

# 클라이언트
npm --prefix client run test
```

### 코드 수정 검증 흐름

**`scripts/check.sh` wrapper** (Phase 11 — D-60) — 자주 쓰는 검증 명령 단축 +
Colima/OrbStack Docker socket 자동 감지 (`DOCKER_HOST=...` prefix 불필요).

```bash
./scripts/check.sh fast              # 빠른 회귀 (~30s, pre-commit 과 동일)
./scripts/check.sh rules             # 룰 도메인 단위 (~3s, Docker 불필요)
./scripts/check.sh server            # 서버 풀 (단위 + IT, ~1m20s)
./scripts/check.sh client            # 클라 풀 (build:check + test + build, ~10s)
./scripts/check.sh all               # server + client (~1m30s)
./scripts/check.sh bot-stress 50     # 봇 시뮬레이션 50 매치
./scripts/check.sh infra             # docker compose + Postgres/Redis 헬스
./scripts/check.sh --help
```

**3 단계 자동화**:

| 단계 | 트리거 | 범위 | 시간 |
| --- | --- | --- | --- |
| pre-commit | `git commit` | `check fast` 위임 | ~30s |
| 로컬 풀 검증 | 수동 | `check server` / `check all` / `check bot-stress N` | ~1~5m |
| GitHub Actions CI | push / PR | server 풀 + client 풀 + bundle-jar smoke 3 job 병렬 | ~5~10m |

**pre-commit 활성화** (repo clone 후 한 번만):
```bash
git config core.hooksPath .husky
chmod +x .husky/pre-commit scripts/check.sh

# 우회 (긴급 수정 시)
git commit --no-verify
```

기존 raw 명령 (`./gradlew :server:test --tests "..."` / `npm --prefix client run test`)
은 그대로 유지 — wrapper 가 위에 얇게 얹힌 형태. 디버깅 시 직접 호출 가능.

---

## 작업 규칙

- 설계 변경(스키마·계약·`docs/*.md`)은 **코드보다 먼저** `docs/decisions.md` 에
  D-NN 항목을 추가합니다. 근거와 번복 이력을 남기는 것이 이 프로젝트의 규약입니다.
- 커밋 게이트는 `./scripts/check.sh fast` (pre-commit 훅과 동일 로직).
- 자세한 행동 규칙은 [CLAUDE.md](CLAUDE.md) 참조.
