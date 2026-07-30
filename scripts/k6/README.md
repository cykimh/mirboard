# k6 부하 시나리오 (M2-C5 — D-92)

```bash
./scripts/check.sh load        # 5 VU, ~35s
./scripts/check.sh load 20     # 20 VU
```

서버가 `:8080` 에 떠 있어야 한다(`./scripts/dev.sh server`). k6 가 로컬에 없으면
`grafana/k6` Docker 이미지로 자동 폴백하므로 설치는 선택이다.

## 역할 분담 — 왜 REST 만 치는가

| 대상 | 도구 | 이유 |
| --- | --- | --- |
| REST (로비/카탈로그/랭킹) | **k6** (`smoke.js`) | HTTP 부하는 k6 가 가장 간단 |
| 인게임 (STOMP 액션·룰 엔진) | **`./scripts/check.sh bot-stress N`** | 이미 있는 `BotMatchSimulationIT` 가 풀매치를 돌린다 |

k6 로 STOMP 를 치려면 `xk6-websockets` 확장을 넣어 **k6 바이너리를 직접 빌드**해야 한다.
"설치 한 줄"이 깨지는 대가에 비해, 서버에는 이미 봇 시뮬레이션이라는 더 현실적인 인게임
부하 수단이 있다(실제 룰 엔진·Redis 락·이벤트 브로드캐스트를 전부 태운다). 그래서
D-92 에서 둘로 나눴다.

## 임계값

`smoke.js` 의 thresholds 는 **로컬 실측에서 역산**했다 — 5 VU 기준 p95 ≈ 37ms, 실패 0건.
여기에 8배 남짓 여유를 둔 값이라, 빨개지면 "조금 느려졌다"가 아니라 "뭔가 깨졌다"로 읽는다.

| 지표 | 임계 | 실측(5 VU) |
| --- | --- | --- |
| `http_req_failed` | < 1% | 0% |
| `http_req_duration` p95 | < 300ms | 37ms |
| `checks` | > 99% | 100% |

수치를 조일 때는 반드시 **측정 후** 조인다. 추측한 임계값은 회귀 가드가 아니라 잡음이다.

## 설계 메모 — 토큰은 setup 에서 한 번만

각 VU 는 `setup()` 이 미리 받아둔 JWT 를 재사용한다. 매 반복 로그인하면 D-90 의
`auth` 버킷(IP 키, 20/분)에 걸려 **부하가 아니라 레이트리밋을 측정**하게 된다 — 초기
버전이 실제로 그래서 429 가 48% 였다. 실 클라이언트도 12h JWT 를 재사용하므로 지금
형태가 현실에 더 가깝다.

부하 대상을 인증 경로 자체로 삼고 싶다면 `mirboard.ratelimit.enabled=false` 로 서버를
띄우고 별도 시나리오를 쓸 것.

## 환경변수

| 변수 | 기본 | 설명 |
| --- | --- | --- |
| `MIRBOARD_BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `MIRBOARD_LOAD_VUS` | `5` | 가상 사용자 수 (`check.sh load N` 이 설정) |
| `K6_RUN_TAG` | (없음) | 계정 이름 접미사 — 재실행 시 계정 충돌 회피 |
