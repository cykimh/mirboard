# 배포 (Fly.io + Postgres + Redis)

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

# 3) Redis — Fly 자체 앱 (D-114, 월 약 $2 고정). 설정은 ops/redis/ 에 있다.
#    (Upstash 무료 DB 는 방치 중 삭제됐고, 종량제는 D-96 폴링 때문에 요금이 튈 수 있어 택하지 않음)
flyctl apps create mirboard-redis -o personal
openssl rand -hex 32 > /tmp/redis-pw && chmod 600 /tmp/redis-pw
flyctl secrets set REDIS_PASSWORD="$(cat /tmp/redis-pw)" -a mirboard-redis --stage
(cd ops/redis && flyctl deploy --local-only --ha=false)
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

# Redis (D-114 — 사설망 mirboard-redis.internal, TLS 없음). 비밀번호는 위 3) 과 같은 값
flyctl secrets set \
  MIRBOARD_REDIS_HOST="mirboard-redis.internal" \
  MIRBOARD_REDIS_PORT="6379" \
  MIRBOARD_REDIS_PASSWORD="$(cat /tmp/redis-pw)" \
  MIRBOARD_REDIS_SSL="false" && rm /tmp/redis-pw
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

**배포 이미지 그대로 검증** — `flyctl deploy` 가 빌드하는 것과 같은 `Dockerfile` 을 로컬
compose 의 Postgres/Redis 에 붙여 띄운다. 첫 배포 전에 한 번 돌려 두면 실패 원인이 Fly
설정인지 이미지인지 갈라진다(2026-10-03 실측: 헬스 UP ~9s, Flyway 10개 검증).

```bash
docker build -t mirboard:deploy-check .
docker run --rm -d --name mirboard-deploycheck -p 18080:8080 \
  -e MIRBOARD_JWT_SECRET="$(openssl rand -hex 32)" \
  -e MIRBOARD_DB_URL=jdbc:postgresql://host.docker.internal:5432/mirboard \
  -e MIRBOARD_DB_USER=mirboard -e MIRBOARD_DB_PASSWORD=mirboardpw \
  -e MIRBOARD_REDIS_HOST=host.docker.internal -e MIRBOARD_REDIS_PORT=6379 \
  -e MIRBOARD_REDIS_SSL=false -e MIRBOARD_ALLOWED_ORIGINS=http://localhost:18080 \
  mirboard:deploy-check
curl -s localhost:18080/actuator/health          # → {"status":"UP",...}
curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/api/games   # → 401 (인증 필요)
docker stop mirboard-deploycheck
```


---

## 게스트 체험 (D-117)

로그인 화면의 「게스트로 바로 체험하기」(`POST /api/auth/guest`)는 **기본 켜짐**이라 새
시크릿이 필요 없습니다. 방문자마다 일회용 게스트 계정을 만듭니다 — D-105 의 공유 데모
계정(`DemoAccountSeeder`)은 같은 userId 를 여러 사람이 써서 좌석 탈취·손패 유출이 생겨
**삭제**했습니다.

| 환경 변수 | 기본 | 뜻 |
| --- | --- | --- |
| `MIRBOARD_GUEST_ENABLED` | `true` | 킬스위치. `false` 면 생성이 403 `GUEST_DISABLED` |
| `MIRBOARD_GUEST_DAILY_CAP` | `200` | UTC 하루 전역 생성 상한(비용 상한). 넘으면 503 `GUEST_UNAVAILABLE` |
| `MIRBOARD_RATELIMIT_GUEST_LIMIT` | `10` | IP(IPv6 /64) 당 하루 생성 한도. 넘으면 429 |
| `MIRBOARD_CLIENT_IP_HEADER` | `Fly-Client-IP` | 레이트리밋 IP 를 읽을 신뢰 헤더(prod 프로필만). 클라가 위조할 수 있는 `X-Forwarded-For` 대신 Fly 프록시가 채우는 값을 쓴다 |

남용 시 긴급 차단(머신 재시작됨):

```bash
flyctl secrets set MIRBOARD_GUEST_ENABLED=false -a mirboard
```

한도만 조이려면:

```bash
flyctl secrets set MIRBOARD_GUEST_DAILY_CAP=100 MIRBOARD_RATELIMIT_GUEST_LIMIT=5 -a mirboard
```

전역 상한의 첫 거절은 ERROR 로그(= Sentry 이벤트, D-107)로, 70% 도달은 WARN 으로 남습니다.

### 배포 후 실측

**IP 위조 내성** — 게스트 할당량을 쓰지 않으려고 로그인 버킷(분당 20)으로 잽니다. 1~20번은
`401`, **21번째는 `429`** 여야 합니다. 21번째도 `401` 이면 `Fly-Client-IP` 를 클라가 위조할 수
있다는 뜻입니다(그때는 XFF 오른쪽 끝 홉을 쓰는 해석기로 바꿔야 함).

```bash
for i in $(seq 1 21); do curl -s -o /dev/null -w "$i %{http_code}\n" -X POST https://mirboard.fly.dev/api/auth/login -H 'Content-Type: application/json' -H "X-Forwarded-For: 10.0.0.$i" -H "Forwarded: for=10.0.1.$i" -H "Fly-Client-IP: 10.0.2.$i" -d "{\"username\":\"ipcheck_$i\",\"password\":\"x\"}"; done
```

**경로 변형 우회 내성** — 같은 로그인 버킷을 경로 문자열만 바꿔 잽니다. Fly 프록시가 경로를
정규화하는지는 확인되지 않았으므로 서버가 직접 막는지 봅니다. 세 루프가 같은 버킷을 쓰므로 앞
루프의 카운트가 넘어오지 않게 **루프마다 1분 간격**을 둡니다. 둘 다 1~20번 `401`, **21번째
`429`** 여야 합니다(21번째도 `401` 이면 필터가 그 변형을 건너뛰거나 다른 버킷을 탄다는 뜻).

```bash
# 인코딩 경로 (/api/auth/login 과 같은 컨트롤러)
for i in $(seq 1 21); do curl -s -o /dev/null -w "$i %{http_code}\n" -X POST 'https://mirboard.fly.dev/api/%61uth/login' -H 'Content-Type: application/json' -d "{\"username\":\"pathcheck_$i\",\"password\":\"x\"}"; done
# X-Forwarded-Prefix (framework 전략이 contextPath 로 바꾼다)
for i in $(seq 1 21); do curl -s -o /dev/null -w "$i %{http_code}\n" -X POST https://mirboard.fly.dev/api/auth/login -H 'Content-Type: application/json' -H 'X-Forwarded-Prefix: /zz' -d "{\"username\":\"prefixcheck_$i\",\"password\":\"x\"}"; done
```

**게스트 생성** — 이 IP 의 하루 10회 중 1회를 씁니다. `201` 이어야 합니다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://mirboard.fly.dev/api/auth/guest -H 'Content-Type: application/json' -d '{}'
```

### 데모 계정 잔재 정리 (D-105 를 운영에서 켠 적이 있을 때만)

`flyctl secrets list -a mirboard` 에 `MIRBOARD_DEMO_*` 가 보이면 지웁니다(이제 아무도 읽지 않음).

```bash
flyctl secrets unset MIRBOARD_DEMO_ENABLED MIRBOARD_DEMO_USERNAME MIRBOARD_DEMO_PASSWORD -a mirboard
```

비밀번호가 공개됐던 `demo` 행이 운영 DB 에 남아 있으면 로그인 불가 해시로 봉인합니다. 데모를
켠 적이 없다면 실제 사용자의 `demo` 계정일 수 있으니 건드리지 않습니다.

```sql
SELECT id, created_at FROM users WHERE username = 'demo';
UPDATE users SET password_hash = '__retired_no_login__' WHERE username = 'demo';
```

---

## 원카드 공개 상태 (`MIRBOARD_ONECARD_STATUS`, D-128)

원카드의 카탈로그 상태는 설정이다(`mirboard.onecard.status`, 코드 기본값은 `application.yml`). 운영에서는 시크릿으로만
덮어쓴다 — `fly.toml` 의 `[env]` 에 같은 키를 두지 않는다(두 곳에 있으면 어느 값이 이기는지 헷갈린다).

| 값 | 허브 카탈로그 | 새 원카드 방 | 진행 중인 원카드 방 |
| --- | --- | --- | --- |
| `AVAILABLE` | 보인다 | 만들 수 있다 | 정상 |
| `COMING_SOON` | "Coming Soon" | 404 `GAME_NOT_AVAILABLE` | **끝까지 정상 진행** |
| `DISABLED` | 안 보인다 | 404 `GAME_NOT_AVAILABLE` | **멈춘다** — 액션은 `GAME_NOT_AVAILABLE`, resync 404, 봇·턴·경쟁 타이머가 오류로 멈추고 '나가기'도 404. 방은 마지막 입장부터 6시간(방 해시 TTL)까지 IN_GAME 으로 남고 결과는 기록되지 않는다 |

**되돌리기는 `COMING_SOON` 으로만 한다.** 새 방만 막고 진행 중인 판은 끝까지 간다. 이미 열려 있던 WAITING 원카드 방은
허브 목록에 남고 입장·준비·시작이 된다(막는 것은 방 만들기뿐이다).

```bash
# 끄기(되돌리기) — 머신이 재시작된다
flyctl secrets set MIRBOARD_ONECARD_STATUS=COMING_SOON -a mirboard
# 다시 열기 — 시크릿을 지우면 코드 기본값으로 돌아간다(공개 전환으로 코드 기본값이 AVAILABLE 이 된 뒤 — 그 전에는
# `secrets set MIRBOARD_ONECARD_STATUS=AVAILABLE`)
flyctl secrets unset MIRBOARD_ONECARD_STATUS -a mirboard
```

- **`DISABLED` 는 진행 중인 원카드 방도 대기 중인 방도 없을 때만.** 경과 시간으로는 보장되지 않는다 — `COMING_SOON` 뒤에도 남은
  WAITING 방은 입장·준비·시작될 수 있고, 방 해시 TTL(6시간)은 **입장할 때마다** 다시 걸린다(게임 상태 키는 저장할 때마다
  6시간씩 밀린다). Redis 에서 직접 확인한 뒤에만 바꾼다 — 아래 출력에 `IN_GAME` 줄도 `WAITING` 줄도 없을 때(빈 출력이거나
  `FINISHED` 만):

  ```bash
  flyctl ssh console -a mirboard-redis
  # 머신 안(sh)에서 — REDISCLI_AUTH 에 Redis 비밀번호(시크릿 REDIS_PASSWORD)를 넣는다
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  redis-cli --scan --pattern 'room:*' | while read -r k; do
    [ "$(redis-cli TYPE "$k")" = hash ] && [ "$(redis-cli HGET "$k" gameType)" = ONE_CARD ] && echo "$k $(redis-cli HGET "$k" status)"
  done
  ```

  (`room:*` 키 가운데 해시이고 `gameType` 이 `ONE_CARD` 인 것만 — 방 해시다. `redis-cli` 7.4 의 `--scan` 에는 `--type` 이
  없어서 키마다 `TYPE` 을 본다. 스크립트는 `redis:7-alpine` 에서 검증했다 — 운영 머신 셸에 `REDIS_PASSWORD` 가 보이는지는
  처음 쓸 때 한 번 확인한다.)

  `WAITING` 방은 `DISABLED` 뒤에도 입장·준비·시작되고(시작 경로에 게임 상태 검사가 없다) 시작하는 순간 위 표의 '멈춘다'가
  된다. `COMING_SOON` 은 새 방을 막으므로 `IN_GAME`·`WAITING` 줄은 늘지 않는다 — 비워질 때까지 기다린다.
- **잘못된 값은 앱 전체 기동 실패다**(빈 값·오타 — 의도된 fail-fast). 사고 중에 쓰는 손잡이이므로 값은 위 명령을
  그대로 복사한다(대문자).
- 시크릿을 바꾸면 머신이 재시작된다. 재시작 순간 봇 차례였던 방은 클라가 다시 붙을 때(resync·게임 토픽 구독) 진행
  킥이 봇 루프와 사라진 경쟁 타이머를 다시 건다(D-130) — 판이 멈춘 채 남지 않는다.
- **시크릿 변경은 앱 전체 재시작이다** — 다른 게임의 진행 중 방도 끊긴다. 콜드 스타트 ~100초가 탈주 유예 120초에 가깝다
  (`fly.toml`·`mirboard.desertion.grace-seconds`) — 진행 중인 방이 많으면 피하고, 바꾼 뒤 재접속을 확인한다.

**경쟁 튜닝**(룰 §9). 환경 변수 이름은 설정 키에서 나온다(Spring relaxed binding). 잘못된 조합(최소 > 최대, 창 ≤ 0)도
기동 실패다(`RaceSettings`). 봇 반응 구간을 바꾸면 튜토리얼의 "1.0~2.5초"(`onecardTutorialSteps.tsx`·`ReactionPractice.tsx`),
창 길이를 바꾸면 "3초"(같은 두 파일, `PRACTICE_WINDOW_MS`)도 같은 배포에서 고친다.

| 환경 변수 | 기본 | 뜻 |
| --- | --- | --- |
| `MIRBOARD_ONECARD_RACE_WINDOW_MILLIS` | `3000` | "원카드!/잡기!" 경쟁 창 길이 |
| `MIRBOARD_ONECARD_BOT_REACTION_OWNER_MIN_MILLIS` · `..._OWNER_MAX_MILLIS` | `1000` · `2500` | 1장 남은 봇이 "원카드!"를 누르는 반응 시간 구간 |
| `MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MIN_MILLIS` · `..._CATCHER_MAX_MILLIS` | `1000` · `2500` | 다른 봇이 "잡기!"를 누르는 반응 시간 구간 |

튜닝 근거는 경쟁 결과 로그다(D-130) — 창이 닫힐 때마다 INFO 한 줄. 사용자별 값이라 메트릭이 아니라 로그로만 남긴다.

```bash
flyctl logs -a mirboard | grep "OneCard race resolved"
# OneCard race resolved: room=… raceId=… outcome=CAUGHT via=PRESS ownerSeat=0 ownerUser=… ownerBot=true
#   bySeat=2 byUser=… byBot=false latencyMs=420 windowMs=3000 lateMs=-
```

`flyctl logs` 는 실시간 스트림이라 기간 분포를 보려면 파일로 받아 둔다(`… | grep "OneCard race resolved" > race-$(date +%F).log`).

`outcome` 은 CALLED·CAUGHT·EXPIRED·CANCELLED, `via` 는 PRESS(사람 누름)·TIMER(봇 누름·창 만료)·DESERTION(창 중 탈주).
`latencyMs` 는 창을 연 뒤 처리까지(누름이면 반응 + 왕복 시간 — 창이 열리자마자의 누름이 반복되는 계정은 자동화를
의심한다), `lateMs` 는 타이머 경로가 정해 둔 마감보다 늦게 처리된 시간(단일 폴러 지연)이다. **집계는 `room`+`raceId` 로
묶어 마지막 줄을 정본으로 센다** — PRESS·TIMER 줄은 저장 전에 찍히므로(DESERTION 은 어댑터가 저장한 뒤에 찍는다), 저장이
실패한 뒤 같은 창이 다른 경로(타이머·킥이 다시 건 타이머)로 닫히면 같은 창이 두 번(다른 결과로) 찍힐 수 있다.

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

---

## Sentry 오류 리포팅 (D-107, 선택)

DSN 을 주지 않으면 `SentryConfig` Bean 자체가 만들어지지 않는다 — 로컬·CI 는 영향이 없다.
배포 환경에서만 켠다:

```bash
flyctl secrets set SENTRY_DSN="https://<key>@<org>.ingest.sentry.io/<project>" \
                   SENTRY_ENVIRONMENT=production \
                   SENTRY_RELEASE="$(git rev-parse --short HEAD)"
```

- **공식 Spring Boot 스타터는 쓰지 않는다.** `sentry-spring-boot-starter-jakarta` 는
  Boot 3 대상이고, Boot 4 에 붙이면 Sentry 자신이 `!Incompatible Spring Boot Version
  detected!` 를 낸다. 대신 `sentry-logback` 어펜더를 코드로 붙인다 — ERROR 로그가
  이벤트로, INFO 는 breadcrumb 으로 올라간다. Boot 4 지원 스타터가 나오면 재검토.
- **PII 는 보내지 않는다**(`send-default-pii=false`). `users` 화이트리스트로 개인정보를
  스키마에서 막아 놓고 예외 리포트로 흘리면 원칙이 무의미하다. MDC 의 userId 는 내부
  식별자라 태그로 붙는다.
- 성능 트레이싱은 기본 끔(`SENTRY_TRACES_SAMPLE_RATE=0.0`). 스타터를 포기해 요청
  트레이싱이 없으므로 켜도 얻는 것이 적다 — 무료 쿼터를 오류 리포트에 쓰는 편이 낫다.

Grafana 는 SaaS 를 붙이지 않았다. 로컬 스택(`--profile observability`)이 정본이고,
프로덕션 메트릭을 보려면 `/actuator/prometheus` 를 외부 Prometheus 가 긁게 하면 된다
(엔드포인트는 `/api/**` 밖이라 인증이 없으니, 공개 배포 시 네트워크 레벨로 막을 것).
