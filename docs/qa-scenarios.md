# 수동 검증 시나리오

REST/STOMP 계약의 **정본**은 [api.md](api.md) · [stomp-protocol.md](stomp-protocol.md) 이고,
이 문서는 손으로 훑을 때 쓰는 대본입니다. 자동 검증은 `./scripts/check.sh server` 가 합니다.

> 아래 절 제목의 Phase 번호는 작성 당시 진행 단계이고, 지금은 기능 구분으로 읽으면 됩니다.
> 티츄 특수 카드 시나리오(마작 소원 / 드래곤 양도 / 피닉스 단독)는 해당 룰의 **유일한
> 수동 검증 대본**이므로 유지합니다.

## Phase 2b — 인증 (Auth) 동작 확인

회원가입 → 로그인 → 본인 정보 조회:

```bash
# 1) 회원가입
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}'
# → {"userId":1,"username":"alice_01"}

# 2) 로그인 (JWT 획득)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01","password":"validpass1"}' | jq -r .accessToken)

# 3) 본인 정보 조회
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
# → {"userId":1,"username":"alice_01","winCount":0,"loseCount":0}
```

예외 케이스:
- 같은 username 재등록 → `409 USERNAME_TAKEN`
- 비밀번호 오류 → `401 BAD_CREDENTIALS`
- 토큰 없는 `/api/me` → `401 UNAUTHORIZED`
- username 규칙 위반 (`^[A-Za-z0-9_]{3,20}$`) → `400 INVALID_INPUT`

## 게스트 체험 (D-117)

가입 없이 들어온 방문자가 한 판을 끝까지 하고, 게스트 제한이 지켜지는지:

```bash
# 1) 게스트 생성 — 201, user.guest=true, username 은 guest-xxxxxxxx
G=$(curl -s -X POST http://localhost:8080/api/auth/guest \
  -H "Content-Type: application/json" -d '{}')
echo "$G" | jq .user
GT=$(echo "$G" | jq -r .accessToken)

# 2) 게스트 금지 행위 — 둘 다 403 GUEST_FORBIDDEN
curl -s -X PUT http://localhost:8080/api/me/password -H "Authorization: Bearer $GT" \
  -H "Content-Type: application/json" -d '{"currentPassword":"x","newPassword":"newpass123"}'
curl -s -X DELETE http://localhost:8080/api/me/avatar -H "Authorization: Bearer $GT"

# 3) 게스트 username 으로는 로그인할 수 없다 — 401 BAD_CREDENTIALS
curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$(echo "$G" | jq -r .user.username)\",\"password\":\"__guest_no_login__\"}"

# 4) JSON 이 아니면 415 (크로스사이트 폼 전송 차단)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/auth/guest \
  -H "Content-Type: text/plain" -d '{}'
```

브라우저(시크릿 창 권장 — 로컬 스토리지가 비어 있어야 처음 온 사람과 같다):

1. 로그인 화면 폼 아래 '또는' + 「게스트로 바로 체험하기」 + 캡션 "가입 없이 12시간 · 전적은
   랭킹에 오르지 않아요" → 누르면 메인으로 간다.
2. 메인 헤더: username 옆 '게스트' 배지, 아바타 버튼은 눌리지 않고 마우스를 올리면 이유가 뜬다.
3. 「새 방 만들기」 → 처음 선택이 **'친구와 하기'**다(D-136 — 게스트도) → '봇과 연습'을 골라 「연습 시작」 → 대기실 없이 게임판이 뜬다 → 한 판을 끝낸다.
4. 로비 채팅을 다른 창(정회원)과 주고받는다 — 게스트도 정회원과 같다.
5. 랭킹 카드에 게스트가 없다. 프로필의 비밀번호 칸은 안내 문구로 바뀌어 있다.
6. 「로그아웃」 → "다시 들어올 수 없어요" 확인창. 취소하면 그대로, 확인하면 로그인 화면.
7. 한도: 같은 IP 에서 하루 11번째 생성은 429 → 로그인 화면이 "이 네트워크에서 오늘 만들 수
   있는 게스트 수를 다 썼어요" + 「회원가입하고 시작하기」 버튼을 보여 준다.

운영에서는 배포 직후 [deploy.md](deploy.md) "게스트 체험 → 배포 후 실측"의 IP 위조 내성 루프와
경로 변형(인코딩 경로·`X-Forwarded-Prefix`) 우회 내성 루프(각각 로그인 21회 — 21번째가 429,
루프 사이 1분)를 한 번씩 돌린다.

## Phase 2c — 게임 카탈로그

```bash
# 카탈로그 조회 (인증 필요)
curl -s http://localhost:8080/api/games -H "Authorization: Bearer $TOKEN"
# → {"games":[{"id":"SKULL_KING","displayName":"스컬킹",
#              "shortDescription":"2~8인 트릭테이킹. 매 라운드 자기 승수를 예측하고...",
#              "minPlayers":2,"maxPlayers":8,"status":"AVAILABLE", ...},
#             {"id":"ONE_CARD","displayName":"원카드",
#              "minPlayers":2,"maxPlayers":6,"status":"AVAILABLE","defaultPlayers":4,"defaultTurnSeconds":30, ...},
#             {"id":"TICHU","displayName":"티츄",
#              "shortDescription":"4인 파트너 카드 게임. 56장 덱과 4장의 특수 카드...",
#              "minPlayers":4,"maxPlayers":4,"status":"AVAILABLE", ...}]}
# 정렬은 상태 → 표시 이름(가나다) — GameRegistry 가 GameDefinition Bean 을 모아 만든다(필드 전체는 docs/api.md).

# 단일 게임
curl -s http://localhost:8080/api/games/TICHU -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/games/SKULL_KING -H "Authorization: Bearer $TOKEN"

# 미등록 게임 → 404 GAME_NOT_AVAILABLE
curl -s http://localhost:8080/api/games/UNKNOWN -H "Authorization: Bearer $TOKEN"
```

새 게임 추가 절차: `domain.game.{newgame}` 패키지에 `GameDefinition` 구현체를
`@Component` 로 만들면 카탈로그/단일조회 자동 노출. 로비/허브/REST 코드 수정 불필요.

## Phase 2d — 방(Room)

```bash
# 방 생성 (자동으로 본인이 host로 입장)
ROOM=$(curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"친구들 한 판","gameType":"TICHU"}' | jq -r .roomId)

# 방 목록 (WAITING 만, gameType 필터 가능)
curl -s "http://localhost:8080/api/rooms?gameType=TICHU" \
  -H "Authorization: Bearer $TOKEN"

# 입장 / 단일 조회 / 퇴장
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/join  -H "Authorization: Bearer $TOKEN"
curl -s        http://localhost:8080/api/rooms/$ROOM        -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/rooms/$ROOM/leave -H "Authorization: Bearer $TOKEN"
```

예외 케이스:
- 방 만석 입장 → `409 ROOM_FULL`
- 이미 입장한 방 재입장 → `409 ALREADY_IN_ROOM`
- IN_GAME 으로 전환된 방 입장 → `409 GAME_ALREADY_STARTED`
- 등록 안 된 gameType → `404 GAME_NOT_AVAILABLE`
- 모르는 roomId → `404 ROOM_NOT_FOUND`

**원자성 보증**: `room_create.lua` / `room_join.lua` / `room_leave.lua` 가 Redis
단일 스레드 위에서 capacity 체크 → players push → 메타 갱신을 한 번에 처리.
`RoomServiceConcurrencyIT` 가 9 스레드 동시 입장으로 capacity=4 위반 0건을 검증.

## Phase 2e — WebSocket / STOMP

- 엔드포인트: `ws://<host>/ws` (raw) + SockJS fallback.
- CONNECT 시 `Authorization: Bearer <JWT>` 헤더 필수 — 없거나 위조면 거절.
- 채널:
  - `/topic/lobby/chat` — 로비 채팅 (서버 발행)
  - `/topic/lobby/rooms` — 방 변경 알림 (`ROOM_UPDATED` / `ROOM_DESTROYED`)
  - 클라 발행: `/app/lobby/chat` `{ "message": "..." }`
- 메시지 envelope: `{ "eventId", "type", "ts", "payload" }`. 상세는
  [`docs/stomp-protocol.md`](stomp-protocol.md).

브라우저에서 빠른 검증 (`@stomp/stompjs` 기준):
```js
import { Client } from '@stomp/stompjs';
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => {
    client.subscribe('/topic/lobby/chat', (m) => console.log(JSON.parse(m.body)));
    client.publish({ destination: '/app/lobby/chat', body: JSON.stringify({ message: 'hi' }) });
  },
});
client.activate();
```

## 전체 시연 (End-to-End)

```bash
# 1) 인프라
docker compose up -d postgres redis

# 2) 백엔드 (Spring Boot 4.0.1, Java 25)
./gradlew :server:bootRun
# → http://localhost:8080

# 3) 프론트엔드 (Vite, 새 터미널)
npm --prefix client install   # 처음 한 번
npm --prefix client run dev
# → http://localhost:5173 — Vite 가 /api 와 /ws 를 8080 으로 proxy
```

브라우저 4개(또는 incognito 창 4개) 로:
1. `/register` 에서 4명의 사용자 가입 (예: `p1` ~ `p4`).
2. 각자 로그인 → **미르보드카페(`/games`)** 진입. 게임 카탈로그·방 목록·랭킹·로비 채팅이
   한 화면에 있다 (게임별 로비 페이지 `/games/:id/lobby` 는 Phase 16(#1)에서 폐지).
3. p1 이 "방 만들기" → 모달에서 티츄 선택 → 생성. p2, p3, p4 가 목록에서 입장.
4. **전원이 "준비"** 를 누르면 `room_ready.lua` 가 WAITING→IN_GAME 을 원자 전이시키고
   `GameStartingEvent` → `TichuRoundStarter` 가 셔플 + 분배 + Redis 저장 → RoomPage 가
   `IN_GAME` 감지 → `GameTable` 마운트 → STOMP CONNECT + `/api/rooms/{id}/resync` →
   본인 손패 수신. (정원만 차면 자동 시작하던 방식은 Phase 16(#2)에서 폐기됐다.)
5. Mahjong 보유자가 첫 리드. 카드 클릭으로 선택 → "내기" → 다른 클라들이 자동 갱신.

스컬킹은 같은 화면에서 방 만들기 시 인원(2~8)을 고르고, 시작하면 예측 제출 → 전원 제출 후
동시 공개 → 트릭 플레이 순서로 진행된다.

**스컬킹 봇 방 확인 (D-119 휴리스틱)**: 사람 1명 + 봇 채우기로 4~6인 방을 열어 몇 라운드
진행하며 본다.
1. 봇 예측이 **0 고정이 아니라 손패에 따라 달라지는가** — 공개된 예측표에 1·2 이상이 섞인다.
   (전원 0 이면 휴리스틱이 아니라 최약수 경로를 타는 것이다.)
2. 예측을 채운 봇이 이후 트릭에서 **탈출·약한 카드로 빠지는가** — 이미 채운 봇이 스컬킹·해적을
   던져 트릭을 더 가져가는 장면이 반복되면 이상.
3. 사람이 게임 중 '나가기'로 탈주하면 그 좌석(유령)은 **여전히 0 예측·최약수**인가 — 봇
   휴리스틱은 봇 좌석에만 쓴다(`rules-skullking.md` §16.5).
4. 서버 로그에 `SkullKing bot policy ... falling back` ERROR 가 없어야 한다(정책 폴백 = 버그).

**스컬킹 라운드 결과·점수표 (D-120)** — 375×667(모바일)과 데스크톱에서 각각 확인한다.

1. **직전 결과 패널**: 라운드 1 을 마치면 화면은 곧바로 라운드 2 예측으로 넘어간다(서버는
   라운드 사이에 멈추지 않는다). 예측 패널·손패 **아래**에 '라운드 1 결과'(7열 표, ✓/✗,
   내 행 강조)가 보이고, 내 정보줄에 `직전 R1 +20 ✓` 가 함께 뜬다. 예측 버튼이 결과 패널에
   가려지거나 밀리지 않는다. '닫기' 를 누르면 그 라운드 결과만 사라지고, 라운드 2 가 끝나면
   라운드 2 결과가 새로 뜬다. 카드 플레이 단계로 넘어가면 패널은 사라진다.
2. **점수표 모달**: 헤더 '점수표'(또는 패널의 '점수표 전체') → 라운드×좌석 표. 셀은 라운드
   점수·✓/✗·작은 `예측/획득`, 합계 행은 누적 점수와 같다. 8인 방은 가로 스크롤되고 R 열은
   고정된다. 끝난 라운드가 없으면 "아직 끝난 라운드가 없습니다".
3. **새로고침·탭 전환 복원**: 라운드 3 예측 중에 새로고침 → 결과 패널과 점수표가 그대로
   복원된다(resync `completedRounds`). 사람만 있는 방에서 매치가 끝난 뒤 탭을 전환했다
   돌아와도(resync) 매치 종료 패널이 사라지지 않는다(`matchResult`).
4. **관전자**: 관전 진입 시 결과 패널은 좌석 바로 아래에 보이고, 점수표도 열린다.
   입력·손패 패널은 없다.
5. **봇 방 라운드 10 종료 화면 유지**: 사람 1 + 봇 방을 끝까지 진행(턴 제한을 짧게 두면
   빠르다). 매치가 끝나면 방은 FINISHED 가 되지만 게임판이 내려가지 않고 매치 종료 패널
   (승자·최종 점수·접힌 '라운드별 점수' 표)과 '메인으로' 가 남는다. 예측·카드 입력은 숨고,
   '나가기' 는 탈주 확인 없이 나간다. 이 상태에서 새로고침하면 기존 "게임이 종료되었습니다"
   카드다(의도된 동작).
6. **강제 종료**: 진행 중에 방장이 게임판 머리 줄 "게임 종료"(봇과 하는 판만 — D-135) 또는
   어드민 강제 종료(`POST /api/admin/rooms/{id}/abort`)를 하면 스컬킹
   게임판이 남고 "게임이 종료되었습니다" 안내 + '메인으로' 가 뜬다(매치 결과 없음). 방
   해시는 FINISHED 후 600s 뒤 사라지므로 그 뒤 '메인으로'·resync 는 방 없음 오류 화면으로
   떨어진다.
7. **테마·터치**: 라이트/다크 모두 적중(녹)·실패(적) 글자가 배경 대비로 읽힌다. 터치 기기에서
   헤더 점수표·채팅·나가기 버튼이 44px 이상이다.

**재접속 시나리오**: 게임 중 한 명이 새로고침 / 탭 닫고 다시 열기 → 동일 토큰으로
복귀 → `useStompRoom` 이 `/resync` 호출 → 게임 상태 (TableView + 본인 손패) 즉시
복원. 다른 플레이어 상태는 변하지 않음.

## 원카드 게임판 확인 (D-129·D-131)

D-131 부터 원카드는 기본 공개(`AVAILABLE`)라 그냥 `./gradlew :server:bootRun` 으로 보인다. 경쟁 버튼을 눈으로 보려면 창 길이와
봇 반응 구간(주인·잡는 쪽의 최소·최대 4개)을 늘려 얹는다. 설정 파일은 만들지 않는다 — `.gitignore` 밖에 두면 `git add -A` 에
딸려 간다.

```bash
MIRBOARD_ONECARD_RACE_WINDOW_MILLIS=20000 \
MIRBOARD_ONECARD_BOT_REACTION_OWNER_MIN_MILLIS=18000 \
MIRBOARD_ONECARD_BOT_REACTION_OWNER_MAX_MILLIS=19000 \
MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MIN_MILLIS=18000 \
MIRBOARD_ONECARD_BOT_REACTION_CATCHER_MAX_MILLIS=19000 \
./gradlew :server:bootRun
```

각각 `mirboard.onecard.race-window-millis`, `bot-reaction-{owner,catcher}-{min,max}-millis` 설정에 대응한다 — `@Value` 도 스프링의
환경 변수 매핑(점·하이픈 → 밑줄, 대문자)으로 받고 `application.yml` 값보다 우선한다.

1. 미르보드카페 → 원카드 카드의 「게임 방법」으로 튜토리얼(12단계)을 연다(D-136 — 봇과 연습은 대기실이 없어 자동으로 뜨지 않는다 —
   친구와 하기 방 대기실에 처음 들어가면 한 번 자동으로 뜬다). 2단계 카드 그림(♥ 빨강, ♠ 검정, 두 조커), 11단계 퀴즈, 12단계 반응 연습 버튼이 칸 안에 뜨는지 본다.
2. 게임판: 상대 좌석은 이름·장수만, 가운데에 뽑을 더미 장수·맨 위 카드(7 이면 지정 무늬)·진행 방향·차례가 보인다. 내 차례에
   낼 수 없는 카드가 흐리다(맨 위와 무늬·숫자가 다르면). 7 을 고르면 무늬 버튼 4개가 뜨고 고르기 전에는 낼 수 없다.
3. 공격을 받으면 "공격받는 중 +N"·"먹기 (N장)". 반격 못 하는 카드를 내면 오류 줄에 문구가 뜨고 × 로 닫힌다.
4. 누군가 1장이 되면 화면 위쪽 무작위 자리에 버튼이 뜬다(주인 "원카드!" 초록, 나머지 "잡기!" 빨강, 안에 남은 시간 막대).
   눌러서 "잡기 성공: 나 → 상대 벌칙 1장"이 가운데에 뜨는지, 상대 장수가 1 늘었는지 본다. 그동안 내기·먹기 버튼은 막힌다
   ("경쟁 중" 라벨). 사람끼리 동시에 눌러 진 쪽에는 빨간 오류 줄 없이 "늦었어요"만 잠깐 뜬다.
5. 판이 끝나면 순위 표(1, 1, 3 식 · 남은 장수 · 다 냄/파산/탈주)가 뜨고 방이 끝나도 게임판이 남는다. '메인으로'는 탈주 확인 없이 나간다.
6. 모바일 폭(375px)과 라이트 테마에서 가로 스크롤 없이 손패가 여러 줄로 감기고, 헤더 버튼이 44px 인지 본다. 조커·경쟁 버튼·
   내기/먹기에 마우스를 올려도(모바일은 탭한 뒤) 글자와 면이 읽히고, 비활성 "내 차례 아님" 라벨도 읽힌다.
7. 브라우저 개발자 도구 네트워크: 카드를 내거나 먹어도 `/resync` 가 다시 불리지 않는다(입장·재연결·탭 복귀(`visibilitychange`·
   `online`) 때만, D-129 — 해소 이벤트가 끝내 안 오는 창의 창당 1회는 예외, D-130).
8. **방 만들기 처음 선택 되돌림(D-130)**: 새 방 만들기에서 티츄 → 턴 제한 60초 → 원카드로 바꾸면 인원 4·턴 30초, 다시
   스컬킹으로 바꾸면 인원 8·턴 '끔'으로 보인다(자동 테스트 `CreateRoomModal.test.tsx` 도 있다).
9. **턴 카운트다운(D-131)**: 턴 30초 방(원카드 기본)에서 가운데 "○○ 차례" 옆에 `⏱ N초`가 1초씩 준다. 5초 이하면 빨간 테두리·
   배경으로 바뀌고 0초에서 멈춘 뒤 곧 자동 먹기(`CARDS_DRAWN`)와 다음 차례로 다시 30초가 된다. 누군가 1장이 되어 경쟁 버튼이
   뜬 동안은 카운트다운이 사라지고, 창이 닫힌 뒤 다음 차례부터 30초로 다시 센다. 내 차례 중간에 새로고침(F5)해도 30초로 돌아가지
   않고 남은 시간에서 이어진다(resync `turnRemainingMs`). 턴 제한 '끔'으로 만든 방에서는 보이지 않는다. 스크린리더가 매초 읽지
   않는다(`aria-live="off"`).
10. **경쟁 버튼·늦은 누름**: 4 의 "늦었어요"는 빨간 오류 줄 없이 잠깐만 뜨고, 버튼에 마우스를 올려도(모바일은 탭한 뒤) 글자가
   읽힌다(전역 `button:hover` 배경에 덮이지 않는지 — 라이트·다크 둘 다).

## 테이블 좌석 배치 확인 (D-132)

배포 뒤 운영 사이트(또는 로컬)에서 봇과 연습 방(D-136)으로 본다. 모바일은 **390px** 폭(브라우저 기기 모드), 데스크톱은 넓은 창 — 라이트·다크
둘 다. 진행은 시계 방향이다: 위에서 내려다볼 때 내(아래) 다음 차례가 내 **왼쪽**이다.

1. **원카드 좌석 배치**: 4인이면 내 다음 차례가 왼쪽 옆줄, 맞은편이 위, 직전 차례가 오른쪽 옆줄. 6인이면 왼쪽 2명(아래→위)·위 1명·
   오른쪽 2명(위→아래), 2인이면 상대가 위에 혼자. 가운데 녹색 테이블 칸에 뽑을 더미·맨 위 카드·방향·차례가 있다.
2. **차례·다음 표시**: 지금 차례 좌석에 테두리, 그 다음 좌석 위 테두리에 "다음" 꼬리표. 다음이 나면 "나" 줄에 "다음". 카드를 낸
   직후·경쟁 창 동안은 테두리와 "다음"이 함께 사라진다. 파산·탈주한 좌석은 흐리게 자리를 지키고 "다음"은 그 좌석을 건너뛴다.
3. **방향 화살표**: 가운데에 "↻ 시계 방향". Q 가 나오면 "↺ 반시계 방향"으로 바뀌고, 다음 차례가 정해지면 "다음"이 반대쪽(내 오른쪽
   방향)으로 옮겨 간다.
4. **내기·먹기 간격**: 손패 아래 "카드 내기"와 "먹기 (N장)" 사이가 손가락 하나(32px) 이상 떨어져 있어 엄지로 내기를 누르다 먹기를
   누르지 않는다. 360·384px(안드로이드 기기 모드)에서도 "카드를 고르세요"·"내 차례 아님" 상태의 버튼 줄이 한 줄이다(차례가 바뀔 때 버튼 줄
   높이가 출렁이지 않는다). 그보다 좁아 줄이 감겨도 두 버튼이 붙지 않는다.
5. **스컬킹**: 8인이면 왼쪽 2·위 3·오른쪽 2(위쪽 반원) — 360px 에서도 윗줄 3명이 한 줄이다(2 + 1 로 감기지 않음). 넓은 화면은 가운데
   칸에, 좁은 화면(≤560px — 폴더블·분할 화면 포함)은 옆줄 아래 전체 폭에 "↻ 시계 방향"과 트릭 레일(낸 순서대로 순번·이름칩 — 카드는 좌석 앞이 아니라 레일에
   놓인다. 390px 8인이면 한 줄에 3~4장). "다음"은 트릭 중 지금 차례 다음 좌석에 붙고, 그 트릭의 마지막 사람 차례·트릭이 끝나 승자가
   리드하기 전·예측 중에는 없다(다음 리드는 트릭 승자 — 트릭이 끝난 순간 엉뚱한 좌석에 "다음"이 깜빡이지 않는다). 탈주(자동) 좌석도
   건너뛰지 않는다. 라이트 테마에서 좌석의 예·획·손 글자와 트릭 레일의 대기 칸·"아직 낸 카드 없음"이 천 위에서 읽힌다. 8인 예측 중 360·375px 에서 윗줄 좌석의 "예 제출 · 획 · 손"
   글자가 겹치지 않는다(모자라면 통계가 다음 줄로 내려간다). 390px 8인 윗줄 3명도 이름이 아바타 아래 줄로 내려가 8자 안팎 보인다
   ("bot_…"처럼 네 글자만 보이면 실패 — 2026-10-13 운영에서 본 것).
6. **관전자**(원카드·스컬킹): 좌석 0 이 아래 칸에 일반 좌석으로 앉고 "나" 줄이 없다. 다음 차례가 좌석 0 이면 아래 좌석에 "다음"이
   붙는다.
7. **티츄 4인**: 내 다음 차례(패스의 '왼쪽' 상대)가 **왼쪽** 좌석, 직전 차례가 오른쪽, 파트너가 위다. 상대가 카드를 내면 그 좌석 쪽에서
   가운데로 날아오고, 채팅 말풍선·이모지도 같은 좌석 옆에 뜬다. 관전자 화면도 좌석 3 이 아래, 좌석 0 이 왼쪽이다.
8. **390px 겹침·넘침**(원카드 2·4·6인, 스컬킹 4·8인, 티츄 4인): 좌석·더미·트릭 레일·손패가 겹치거나 가로로 넘치지 않는다. 옆줄 좌석은
   이름이 아바타 아래 줄로 내려가 8자 안팎 보인다(봇이 모두 "bo…"로 보이면 실패). 그보다 긴 이름은 말줄임 — 데스크톱은 마우스를 올리면
   전체 이름(title)이 보이고, 모바일은 title 이 뜨지 않는다. 스컬킹 옆줄 좌석은 이름 + 예측·획득만(손패 장수 숨김) — 윗줄 좌석과
   데스크톱은 손패 장수까지 보인다. 원카드 가운데 칸(≈138px)에서도 뽑을 더미와 맨 위 카드가 한 줄이고(7 의 지정 무늬 배지가 붙은 직후만
   두 줄) 손패 첫 줄이 화면 아래 버튼 줄에 가려지지 않는다. 게스트(`guest-…`)·긴 봇 이름의 "차례" 배지는 가운데 칸 안에서 줄을 바꾸고
   옆줄 좌석에 걸치지 않는다. 원카드 경쟁 버튼이 위쪽 좌석을 최대 3초 가릴 수 있다(설계상 그대로).
9. **강제 종료 뒤**(원카드·스컬킹): 방장이 게임판 머리 줄 "게임 종료"를 누르거나 운영자가 끝내 결과 없이 방이 끝나면, 게임판에 "게임이
   종료되었습니다."와 함께 차례 테두리·"다음"·"내 차례"·가운데 차례 이름이 남지 않는다.

## 방장 강제 종료 확인 (D-135)

1. **봇 판**: 봇과 연습 방(원카드·스컬킹·티츄, D-136)을 시작하면 방장 게임판 머리 줄에 "게임 종료"가 보인다. 누르면 확인 창("봇과 하던 이 판은
   기록 없이 끝납니다") — 취소하면 그대로, 확인하면 방이 끝난다(원카드·스컬킹은 게임판에 "게임이 종료되었습니다.", 티츄는 종료 카드).
   전적·탈주 수는 바뀌지 않는다.
2. **사람 판**: 사람이 한 명이라도 더 있으면 방장에게도 버튼이 없다. `curl -X POST …/api/rooms/{id}/abort`(방장 토큰)는
   `409 HUMAN_PLAYERS_PRESENT` 이고 게임은 계속된다.
3. **방장 아님·관전자**: 버튼이 없다.
4. **재접속 배너**: 3분 넘게 끊기면 "게임 중 2분 넘게 끊기면 탈주로 처리됩니다"(예전 "호스트가 강제 종료할 수 있음"은 사라짐).

## 방 초대 링크 확인 (D-133·D-134)

배포 뒤 운영 사이트에서 본다(미리보기는 서버가 빌드된 `index.html` 을 고쳐 주므로 Vite 개발 서버로는 볼 수 없다). 휴대폰 한 대 + 다른
휴대폰(또는 시크릿 창) 하나로 친구 역할을 한다.

```bash
# 미리보기 HTML — 토큰 없이 200, 그 방의 og 메타 (방 ID 는 대기실 주소창에서)
curl -s https://mirboard.fly.dev/rooms/<방ID> | grep -E '<title>|og:|twitter:'
# → <title>원카드 · 금요일 한 판 — 미르보드카페</title>
#   <meta property="og:description" content="alice님이 초대했어요 · 4인 방 · 누르면 바로 입장" /> …
#   og:url 은 https://mirboard.fly.dev/rooms/<방ID> 여야 한다 — http·내부 주소(:8080)면 forwarded 헤더 반영 실패
curl -sI https://mirboard.fly.dev/rooms/<방ID> | grep -i cache-control   # → no-cache
# 없는 방 → 사이트 공통 미리보기("미르보드카페 — 티츄·스컬킹·원카드") 그대로
curl -s https://mirboard.fly.dev/rooms/00000000-0000-0000-0000-000000000000 | grep og:title
# Accept 가 HTML 이 아니어도 406 이 아니라 같은 미리보기(스크래퍼마다 Accept 가 다르다)
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Accept: application/json' https://mirboard.fly.dev/rooms/<방ID>
# → 200 text/html;charset=UTF-8
```

1. **휴대폰 공유창**: 대기실 "초대 링크" → 휴대폰 공유창이 뜨고 카카오톡이 목록에 있다. 보내고 돌아오면 "초대 링크를 보냈어요". 공유창을
   닫으면(취소) 아무 알림이 없다. 데스크톱 브라우저(포인터가 마우스)는 Chrome·Edge·Safari 처럼 공유창이 있어도 공유창 없이 "링크를 복사했어요"
   — 붙여 넣으면 `https://…/rooms/<방ID>`. 게임이 시작되면(게임판) 초대 버튼이 없다. 꽉 찬 대기실이면 버튼 옆 설명이 "자리가 다 찼어요 —
   링크를 받은 친구는 관전으로 들어와요"다(사람으로 꽉 찬 방 — D-136 이후 친구와 하기 방에는 봇이 없고, 봇과 연습 방에는 대기실·초대가 없다).
2. **카톡 미리보기**: 카톡 말풍선에 "{게임} · {방 이름}"과 "{방장}님이 초대했어요 · {정원}인 방 · 누르면 바로 입장"이 뜬다(이미지 없는 글자
   미리보기). 방 이름에 `"`·`<`·이모지를 넣어도 깨지지 않는다. 카톡은 같은 주소의 미리보기를 한동안 저장한다 — 바뀐 내용을 다시 보려면
   카카오 개발자 "공유 디버거"에서 캐시를 지운다.
3. **게스트로 바로 입장**: 친구 휴대폰(로그인 안 됨)에서 링크 → 로그인 화면 맨 위에 "방에 초대받았어요 — 가입하지 않아도 게스트로 바로
   들어갈 수 있어요", 그 아래 "게스트로 바로 입장"이 아이디 입력란보다 **앞**에 있다 → 누르면 그 방 대기실(빈자리면 참가자로). 같은 화면에서
   로그인해도, "회원가입"으로 넘어가 가입해도 그 방으로 간다. 그냥 `/login` 으로 온 사람은 지금 배치 그대로(폼 → 또는 → "게스트로 바로
   체험하기"). 이미 로그인한 브라우저에서 링크를 열면 로그인 화면 없이 바로 그 방이다(참가자면 재접속). 같은 링크를 두 탭에서 거의 동시에
   열어도 둘 다 그 방이다("User already in room" 같은 문구가 보이면 실패).
4. **카톡 앱 내 브라우저**: 카톡에서 링크를 누르면 카톡 안 브라우저로 열린다 — 로그인 토큰이 기본 브라우저와 따로라 처음이면 3번의 로그인
   화면이다. 그 안에서 "초대 링크"를 눌러 공유창·복사가 막히면 "직접 복사해 주세요" 입력란에 링크가 **선택된 채** 보인다(길게 눌러 복사).
   **iOS(아이폰 카톡 앱 내 브라우저·사파리)에서도** 입력란이 뜨면 링크 전체가 선택돼 있는지, 입력란을 다시 눌러도 전체 선택인지 본다
   (iOS 는 읽기 전용 입력란에 `select()` 만으로 선택이 안 잡히는 경우가 있어 `setSelectionRange` 를 함께 부른다 — FR-6).
5. **자리 없음 → 바로 관전 + 안내**: 꽉 찬 대기실(봇 자리 포함)·이미 시작한 방의 링크를 열면 묻지 않고 관전으로 들어가 "관전 중" 대기실이나 관전자
   게임판이 보이고, 화면 맨 위에 "자리가 없어 관전으로 들어왔어요"(대기실) / "게임이 이미 시작돼 관전으로 들어왔어요"(게임판)가 뜬다 — [닫기] 또는
   8초 뒤 사라진다. 게임판(채팅·버튼)을 가리지 않는지, 390px 폭에서 좌우 여백이 있는지 본다. 같은 탭에서 새로고침하면, 그리고 허브에서 "구경하기"로
   들어가면 안내가 뜨지 않는다. 열려는 순간 마지막 자리가 차는 경합이면 "자리가 없어요 — 관전할까요?" 패널 → [관전하기] 로 같은 화면(안내 없음),
   [메인으로] 로 허브.
6. **방 없음**: 모두 나가 사라진 방의 링크 → "방이 없어졌어요" + [메인으로] ("Room not found" 같은 서버 문구가 보이면 실패). 그 링크의 카톡
   미리보기는 사이트 공통 미리보기다(새로 공유한 경우 — 이미 저장된 말풍선은 예전 방 미리보기가 남을 수 있다).

7. **휴대폰 방장이 공유 뒤 카톡에서 기다림(D-134)**: 휴대폰으로 봇 없이 방을 만들고 "초대 링크" → 카카오톡으로 보낸 뒤 **카톡에서 60초
   기다리거나 화면을 잠근다**(백그라운드 10~15초면 소켓이 죽는다). 그동안 친구가 링크를 열면 "방이 없어졌어요"가 아니라 **그 방 대기실에
   앉는다**. 방장이 브라우저로 돌아오면 자리 그대로이고 친구가 보인다. 방장이 떠나기 전에 "준비"를 눌러 두었다면 끊긴 동안 친구 화면에서
   방장의 "준비됨"이 꺼지고, 돌아와도 풀린 채다(다시 누른다 — 그 사람 없이 시작되지 않게). 2분(120초) 넘게 떠나 있었고 혼자였다면 돌아온
   화면이 "방이 없어졌어요" + [메인으로]다("Room not found" 가 보이면 실패).
8. **같은 방 두 탭 중 하나 닫기(D-134)**: 데스크톱에서 같은 방 주소를 두 탭으로 연다(둘 다 그 방). 한 탭을 닫아도 남은 탭은 **자리를 유지**하고
   (참가자 목록에 그대로, "방이 종료되었습니다." 없음) 다른 사람 화면에서도 그대로다. 혼자 있는 방에서 해도 방이 사라지지 않는다.

돌아갈 주소 위조(`//evil.com`·`/\evil.com`·`https://…`·`javascript:`)는 화면에서 만들기 어려워 단위 테스트(`returnPath.test.ts`·
`LoginPage.test.tsx`·`RegisterPage.test.tsx`)가 맡는다.

## 분산 시연 (멀티 인스턴스, Phase 6D)

기본은 단일 인스턴스 + `mirboard.messaging.gateway=in-memory`. 멀티 인스턴스에서
STOMP broadcast / 도메인 이벤트를 Redis Pub/Sub 위에서 흐르게 하려면:

```bash
# 터미널 1 — 8080 인스턴스
export MIRBOARD_MESSAGING_GATEWAY=redis
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"
MIRBOARD_PORT=8080 ./gradlew :server:bootRun

# 터미널 2 — 8081 인스턴스 (같은 Redis 사용)
export MIRBOARD_MESSAGING_GATEWAY=redis
export MIRBOARD_JWT_SECRET="local-dev-secret-must-be-at-least-32-bytes-long-please"
MIRBOARD_PORT=8081 ./gradlew :server:bootRun
```

검증 시나리오:
1. 클라 A 가 `ws://localhost:8080/ws`, 클라 B 가 `ws://localhost:8081/ws` 로 STOMP 연결.
2. 둘 다 `/topic/lobby/rooms` 구독.
3. 클라 A 가 `POST http://localhost:8080/api/rooms` 로 방 생성.
4. 클라 B 가 `ROOM_UPDATED` 이벤트 수신 — Redis Pub/Sub 으로 다른 인스턴스에 전파됨.

Sticky session 불필요 — 사용자가 어느 인스턴스에 붙어 있든 자신의 인스턴스 broker
가 STOMP 프레임을 전달. 방 입장 시 `room:{id}` HASH / `room:{id}:players` LIST 가
Redis 단일 진실 공급원이라 두 인스턴스가 같은 상태를 본다.

**한계 (현재 시점)**:
- 도메인 이벤트(ApplicationEvent)는 인스턴스 간에 전파하지 않는다 — 발행한 인스턴스에서만
  리스너가 돈다(D-116). 리스너 부수효과(매치 기록·칩 정산·라운드 딜링)는 공유 저장소에 쓰이고
  클라 프레임은 `stomp:routes` 로 이미 모든 인스턴스에 가므로, 재처리는 중복 기록일 뿐이다.
- 게임 액션 처리 락 (`room:{id}:lock`) 은 Redis SET NX 라 이미 분산 안전.

## Phase 6 시연 체크리스트

Phase 6 (E/A/C/D) 의 주요 UX/운영 기능을 사용자 직접 클릭으로 검증할 시나리오 모음.
사전 코드 점검은 완료 — 모든 시나리오 진행 가능. 실패 시 디버깅 포인트는 각 항목
끝에 명시.

### 시나리오 1 — Mahjong 소원 (D-109)

1. 게임 시작 (정원 + 전원 준비 → IN_GAME). **봇과 연습 방으로도 검증 가능**(D-136 — 만들자마자 시작) — 소원 창이
   더 이상 다음 플레이어의 속도에 좌우되지 않는 것이 이 시나리오의 핵심이다.
2. Dealing(8) → Dealing(14) → Passing → Playing 진입까지 Ready/카드 패스 진행.
3. 첫 리드 차례 (헤더 `현재 차례`) 가 Mahjong 보유자 (손패에 rank=1 카드) 인 탭에서
   Mahjong 클릭 → "내기".
4. **기대**: 카드가 **아직 나가지 않고** 소원 모달이 뜬다. rank 2~14 그리드 +
   [소원 없이 내기] / [소원 지정하고 내기]. 모달은 시간이 지나도 닫히지 않는다.
5. **취소 확인**: esc 를 눌러 모달을 닫는다. 카드가 나가지 않고 선택이 유지되어야 한다.
   다시 "내기" 를 눌러 모달을 연다.
6. rank 선택 후 [소원 지정하고 내기] → 카드가 나가면서 헤더에 `활성 소원: N` 표시.
7. 다른 클라 탭 헤더에서도 동일 `활성 소원: N` 확인 → STOMP fan-out 정상.
8. **봇 소원 확인**: 봇이 마작을 리드한 라운드에서도 `활성 소원: N` 이 뜨는지 본다
   (봇(휴리스틱, D-118)은 내 손에 없고 4장이 다 나오지 않은 최고 랭크로 소원을 건다 — 그런
   랭크가 없을 때만 소원 없이 낸다).
9. **실패 시**: 서버 로그 `Action rejected: PLAY_CARD reason=WISH_OUT_OF_CONTEXT` 검색.
   `wishRank` 를 실으려면 낸 카드에 Mahjong 이 포함되어야 한다.
10. **소원 해제 (D-126)**: 누군가 소원 숫자를 내면(또는 소원이 걸린 채 용 트릭을 양도하면) 모든 탭에서
    `활성 소원` 표시가 바로 사라진다. DevTools 네트워크 탭에서 카드를 낼 때 `/resync` 요청이 **나가지
    않는지** 본다 — 라운드가 끝나는 플레이(다음 라운드 시작)와 단계 전환(티츄 선언 마감·패스 교환)에서만
    나가는 것이 정상이다. 예전에는 카드를 낼 때마다 전원이 resync 했다.

### 시나리오 2 — Dragon 트릭 양도 (6E-2)

1. Dragon 보유자가 단독 리드 또는 다른 카드 위에 Dragon 단독 플레이.
2. 다른 3명이 모두 PASS → 트릭이 본인에게 닫힘.
3. **기대**: `GiveDragonTrickModal` 자동 노출. 상대팀 두 좌석만 표시.
4. 한 좌석 선택 후 "양도" → 헤더 `누적 A:B` 변화 (Dragon +25 + 트릭 카드 점수).
5. **실패 시**: BOMB 으로 누가 깼다면 currentTop 이 Dragon 아님 → 모달 안 뜸. 정상.
   currentTurnSeat 이 본인이 아니면 트릭이 아직 안 닫힌 상태.

### 시나리오 3 — Phoenix 단독 SINGLE (6E-3)

1. 다른 단일 카드 위에 Phoenix 단독 SINGLE 플레이.
2. **기대**: 트릭 영역에 보라색 "Phoenix +0.5" 배지 + 호버 시 비교 룰 툴팁
   (Dragon 만 못 이김).
3. 다음 플레이어가 더 높은 SINGLE 로 이기면 currentTop 변경 + 배지 사라짐.

### 시나리오 4 — 관전 모드 (6A-5/6A-6)

1. 5번째 사용자 (예: `spec_user`) 가 미르보드카페(`/games`) 진입 → "방 ID 로 관전 진입" 입력 박스에
   IN_GAME 방 ID 붙여넣기 → "구경하기".
2. **기대**: GameTable 표시되되 손패 영역 / 액션 버튼 / 모달 모두 숨김.
   "관전 중 — 본인 손패는 표시되지 않습니다." 배너 노출.
3. "나가기" 버튼 → `DELETE /api/rooms/{id}/spectate` 호출 → 미르보드카페로 복귀.
4. **추가 보안 검증** (옵션): 관전자가 `curl -X POST /app/room/{id}/action` 직접
   호출 시 `NOT_IN_ROOM` 에러 (실제 UI 에선 액션 버튼 자체가 안 보임).

### 시나리오 5 — 멀티 인스턴스 Redis fan-out (6D)

위 "분산 시연 (멀티 인스턴스, Phase 6D)" 섹션의 단계 따라 진행.

추가 확인: `redis-cli MONITOR` 로 `PUBLISH stomp:routes ...` 명령이 흐르는지 관찰.
`PUBLISH domain:event ...` 는 **나오지 않아야** 한다(D-116 — 도메인 이벤트는 로컬 처리).
게임 한 판을 끝낸 뒤 `tichu_match_results` 에 그 방 행이 1개인지도 본다(2개면 중복 기록).

### 시나리오 6 — 게임별 튜토리얼 (D-121)

1. 새 시크릿 창(빈 localStorage)으로 로그인 → 미르보드카페.
2. **기대**: 허브에서는 튜토리얼이 **자동으로 뜨지 않는다**. 헤더에 '게임 방법' 버튼이 없고,
   티츄·스컬킹·원카드 게임 카드 3장 모두에 '게임 방법' 버튼이 있다(튜토리얼이 없는 게임 카드에는 없음).
3. 스컬킹 카드 '게임 방법' → 스컬킹 13단계, 티츄 카드 → 티츄 9단계, 원카드 카드 → 원카드 12단계(퀴즈·반응 연습 포함)가
   열린다. 닫는다.
4. 다른 시크릿 창(빈 localStorage)으로 스컬킹 방을 만들거나 입장 → **대기실 첫 입장에 스컬킹
   튜토리얼이 1회 자동으로 뜬다**. 닫고(✕ 또는 마지막 '시작하기') 나갔다 다시 들어오면 안 뜬다.
   대기실 헤더 '게임 방법' 으로는 언제든 다시 연다.
5. 같은 창에서 티츄 방 입장 → 티츄 튜토리얼이 따로 1회 뜬다(게임별 키 격리). 기존에 티츄
   튜토리얼을 본 계정·브라우저(`mirboard.tutorial.seen.v1` 있음)는 티츄 대기실에서 안 뜬다.
6. 스컬킹 게임판 헤더 '규칙' → 같은 튜토리얼이 열린다. 게임판에서는 자동으로 뜨지 않는다.
   턴 제한 방이면 보는 동안에도 타이머가 흐르는 것이 정상이다(title 툴팁 고지).
7. 다이얼로그 안의 카드 칩(② 카드 구성, ⑦~⑨ 예시, ⑫ 퀴즈)이 **색이 채워져** 보이는지
   라이트/다크 양쪽에서 본다. ⑫ "누가 이길까?" 에서 칩을 고르면 정답/해설, '다음 문제'로 4문제 순환.
8. **실패 시**: 칩이 투명(배경 없음)이면 `.tutorial-body` 에 `sk-tokens` 클래스가 붙었는지,
   `18-skullking-table.css` 끝의 `.sk-tokens` 블록이 빌드에 들어갔는지 확인. 자동 노출이 다시
   뜨면 localStorage 의 `mirboard.tutorial.skull_king.seen.v1` 값을 본다.

### 운영 카운터 점검 (시연 도중)

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^mirboard_'
```

기대 출력:
- `mirboard_room_created_total`
- `mirboard_room_joined_total`
- `mirboard_game_started_total{gameType="TICHU"}`
- `mirboard_round_completed_total`
- `mirboard_match_completed_total`
- `mirboard_action_rejected_total`

각 카운터가 0 이상의 값으로 나오면 6A-3/6A-4 정상.

### 로그 MDC 확인

서버 stdout 의 로그 라인이 `HH:mm:ss.SSS LEVEL [thread] logger [user=N room=R event=-]
- msg` 형식인지 확인. 액션 처리 / 방 변경 시 `user=` 와 `room=` 가 채워지면 6A-1 성공.

### 시연 실패 시 보고 가이드

각 시나리오 실패 시 다음 4가지를 함께 보고:
1. 실패한 시나리오 번호 + 단계.
2. 서버 stdout 의 마지막 ~30줄 (특히 `Action rejected` 또는 `Failed to ...`).
3. 브라우저 콘솔의 STOMP 메시지 / API 응답 에러.
4. 재현 가능한지 (1회성 / 반복).

## 방 만들기 두 갈래 확인 (D-136)

1. **처음 선택**: 「새 방 만들기」는 '친구와 하기'로 열린다(게스트도). 봇 채우기 체크박스가 없다.
2. **봇과 연습**: '봇과 연습'을 고르면 방 이름·목표 점수·판돈이 사라지고 인원(가변 게임)·턴 제한만 남는다("봇 N명과 함께").
   「연습 시작」 → 대기실 없이 곧바로 게임판, 머리 줄에 "🤖 혼자 연습". 다른 창의 허브 '대기 중인 방'에 이 방이 뜨지 않는다.
   방장 "게임 종료"(D-135)가 보인다.
3. **친구와 하기 + 지금 시작**: 원카드 4인 방을 만들고 두 사람이 들어와 둘 다 준비하면 방장에게 "지금 3명으로 시작"이 보인다 →
   누르면 3인 게임이 시작되고 세 사람 모두 손패가 보인다. 한 사람이 준비를 풀면 버튼이 사라지고 "2명 이상 모이고 모두 준비하면 …" 안내.
   방장이 아닌 사람에게는 버튼이 없다. 정원(4)이 다 차면 버튼 없이 전원 준비로 시작한다.
4. **티츄**: 친구와 하기 대기실에 "지금 N명으로 시작"도 안내도 없다(4인 고정).
5. **대기실 안내**: "(봇은 자동 준비)"가 없다.
