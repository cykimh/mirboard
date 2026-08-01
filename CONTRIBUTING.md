# 기여 가이드

Mirboard 를 로컬에서 돌리고 검증하는 방법. 프로젝트가 무엇인지는 [README](README.md),
설계 배경은 [docs/architecture.md](docs/architecture.md) 를 먼저 보세요.

> 검증 환경: macOS (Apple Silicon). Gradle wrapper(`gradlew`)와 `build.gradle.kts` 는
> 리포에 포함돼 있습니다.

## 런타임 요구사항

| 구성 요소 | 버전 |
| --- | --- |
| JDK | **Java 25 (LTS)** |
| Build | Gradle 9.4.1 (wrapper 동봉, Kotlin DSL) |
| Backend | Spring Boot **4.0.1** (Jakarta EE 11 / Spring Framework 7) |
| Frontend | **Node 20+**, Vite + React 18 + TypeScript |
| Infra | PostgreSQL 16, Redis 7 (docker-compose 제공) |

Virtual Threads 가 기본 활성(`spring.threads.virtual.enabled=true`)이고 sealed
interface + pattern-switch 를 적극 쓴다. 모든 import 는 `jakarta.*` — `javax.*` 금지.

---

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

---

## 빠른 실행 — `scripts/dev.sh`

아래 "서버 빌드 / 실행"의 수동 절차를 감싼 래퍼. 어느 디렉터리에서 실행해도 repo 루트로
이동하고, dev 환경변수(JWT/DB/Redis)는 `application.yml` 기본값이 들어 있어 추가 export 가
필요 없다.

| 서브커맨드 | 하는 일 |
| --- | --- |
| `up` | postgres+redis 기동 + Flyway 마이그레이션. 멱등 |
| `server` | `up` 보장 후 `./gradlew :server:bootRun` (:8080) |
| `client` | Vite dev (:5173, `/api`·`/ws` → :8080 proxy) |
| `bundled` | React 번들 동봉 **단일 프로세스** — :8080 만으로 prod 유사 확인 |
| `all` | `up` + 서버(백그라운드 로그) + 클라(포그라운드). Ctrl-C 로 정리 |
| `down [--purge]` | 컨테이너 중지(데이터 보존). `--purge` 면 볼륨까지 삭제 = **DB 초기화** |

```bash
./scripts/dev.sh all            # 로컬 풀스택 한 방에
./scripts/dev.sh bundled        # localhost:8080 단독 확인
./scripts/dev.sh down --purge   # DB 초기화 후 처음부터
```

로컬 개발 자격증명(개발 한정): Postgres `mirboard / mirboardpw`, DB `mirboard`, :5432 /
Redis 비밀번호 없음, :6379. 검증·테스트는 `scripts/check.sh` 가 담당한다.

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

브라우저로 http://localhost:5173 접속 → 회원가입 → 방 만들기 → **정원이 차고 전원이 준비**를
누르면 시작한다. 빈 좌석은 봇이 채우고 봇은 입장 즉시 자동 준비되므로, 혼자서도 준비만
누르면 바로 시작된다. (정원만 차면 자동 시작하던 방식은 Phase 16(#2)에서 폐기됐다.)

포트가 이미 쓰이는 중이면 `MIRBOARD_PORT` 로 서버 포트를, `npm --prefix client run dev --
--port 5174` 로 클라 포트를 바꾼다. Gradle 데몬이 옛 설정을 물고 있으면 `./gradlew --stop`.

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
  --tests "com.mirboard.domain.game.skullking.*" \
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
