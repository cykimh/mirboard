# 배포 (Fly.io + Postgres + Upstash Redis)

시연용 단일 머신 배포. Tokyo(`nrt`) 리전, 비용 약 $5~10/mo.
`fly.toml` 은 `min_machines_running=0` + `auto_stop_machines="stop"` 이라 유휴 시 머신이
멈춥니다 — **첫 접속에 콜드 스타트 약 5초**가 걸리는 대신 비용이 거의 들지 않습니다.

### 0. 사용자 사전 셋업 (한 번)

```bash
# 1) Fly.io
brew install flyctl                      # macOS
flyctl auth signup                       # 또는 flyctl auth login
flyctl apps create mirboard              # 앱 이름은 fly.toml 의 app= 값과 일치 (전역 유니크)

# 2) Postgres (Fly Postgres / Supabase / Neon 중 택1)
#    Fly Postgres 예시 — Tokyo, dev preset:
flyctl postgres create --name mirboard-db --region nrt --initial-cluster-size 1 \
        --vm-size shared-cpu-1x --volume-size 1
flyctl postgres attach --app mirboard mirboard-db
#    → 자동으로 DATABASE_URL secret 이 셋됨. 본 앱은 별도 명명을 쓰므로 아래
#       MIRBOARD_DB_URL 로 다시 설정한다 (Postgres URI → jdbc URL 변환).

# 3) Upstash Redis (Tokyo 리전, TLS)
#    https://console.upstash.com 에서 "Create Database" → Region: ap-northeast-1
#    → host/port/password 메모.
```

### 1. Secret 셋업

```bash
# JWT 시크릿 (32바이트 이상)
flyctl secrets set MIRBOARD_JWT_SECRET="$(openssl rand -hex 32)"

# Postgres
flyctl secrets set \
  MIRBOARD_DB_URL="jdbc:postgresql://<pg-host>:5432/mirboard?sslmode=require" \
  MIRBOARD_DB_USER="<user>" \
  MIRBOARD_DB_PASSWORD="<password>"

# Upstash Redis (TLS 포트 보통 6380)
flyctl secrets set \
  MIRBOARD_REDIS_HOST="<your-db>.upstash.io" \
  MIRBOARD_REDIS_PORT="6380" \
  MIRBOARD_REDIS_PASSWORD="<password>" \
  MIRBOARD_REDIS_SSL="true"
```

### 2. 첫 배포

```bash
# 리포 루트에서:
flyctl deploy

# 헬스 체크
flyctl status
flyctl logs

# 도메인 (기본 https://mirboard.fly.dev) 접속해 회원가입 → 게임 시작.
```

### 3. 로컬에서 prod jar 검증 (옵션)

Docker 빌드 없이도 Spring Boot 의 정적 서빙을 한 번에 검증할 수 있다:

```bash
# 클라 번들 + Spring bootJar 같이 빌드 (단일 jar 안에 React 포함)
./gradlew :server:bootJar -PbundleClient

# 실행
SPRING_PROFILES_ACTIVE=prod \
MIRBOARD_DB_URL="jdbc:postgresql://127.0.0.1:5432/mirboard" \
MIRBOARD_REDIS_SSL=false \
MIRBOARD_JWT_SECRET="$(openssl rand -hex 32)" \
java -jar server/build/libs/server-0.1.0-SNAPSHOT.jar
# → http://localhost:8080 에 React + REST + STOMP 모두 같은 origin
```


---

## 데모 계정 (D-105)

쇼케이스용 고정 계정. **기본 꺼짐** — 공개 비밀번호를 가진 계정이 로컬·CI 에 생기면 안 되므로
배포 환경에서만 켭니다. Flyway 마이그레이션이 아니라 환경변수 게이트인 이유이기도 합니다:
켜고 끄는 것이 설정 한 줄이고, 되돌리는 데 새 마이그레이션이 필요 없습니다.

```bash
flyctl secrets set \
  MIRBOARD_DEMO_ENABLED=true \
  MIRBOARD_DEMO_USERNAME=demo \
  MIRBOARD_DEMO_PASSWORD="<8자 이상>"
```

비밀번호가 비어 있으면 시더가 **경고만 남기고 건너뜁니다**(약한 계정 자동 생성 방지).
계정은 일반 사용자와 동일하게 취급되어 레이트리밋·정지·모더레이션이 그대로 적용됩니다.
구현: `server/.../infra/config/DemoAccountSeeder.java`.

---

## CD (GitHub Actions)

`.github/workflows/deploy.yml` — `main` 푸시 + 수동 실행(`workflow_dispatch`).
**`FLY_API_TOKEN` 시크릿이 없으면 잡이 스스로 건너뜁니다**(미설정을 실패로 만들지 않음).

```bash
flyctl auth token
```

```bash
gh secret set FLY_API_TOKEN --body "<위 명령의 출력>"
```

배포 전 게이트는 클라 `tsc`+`vitest` + 서버 컴파일까지입니다. 전체 통합 테스트는 CI
워크플로가 담당합니다 — 배포 경로에서 Testcontainers 를 또 돌리면 배포가 20분씩 걸립니다.
`concurrency: deploy-production` + `cancel-in-progress: false` 로 배포 중간 취소를 막습니다.

---

## 환경 변수

단일 진실원은 [.env.example](../.env.example) 입니다. 배포 시 필요한 시크릿은 위
"Secret 셋업" 절 참조.
