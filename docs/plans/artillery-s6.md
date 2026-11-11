# 씨앗포 S6 — 실시간 이동·조준 공유 (설계)

> 상태: **S6a 서버·포트(D-250)·S6b 클라(D-251) 구현 완료(2026-11-11 — §10·§11)** · 설계 D-228·D-229 · 상위 기획 `docs/plans/artillery.md` §9 S6 행 · 룰 정본 `docs/rules-artillery.md`
> 포트 정본 `docs/game-port.md` · STOMP 정본 `docs/stomp-protocol.md`
> 단계: **S6a 서버·포트**(지금 바로 가능) → **S6b 클라**(스킨 층 D-227 이 main 에 들어간 뒤). 관제 세션이 단계마다 검증해 main 에 넣는다.

## 0. 한 줄 요약

차례 주인이 걸으면 **걸음이 그 자리에서 서버에 확정**되고(새 액션 `MOVE`, 공개 순번 이벤트 `UNIT_MOVED`), 포신 각도·고른 탄은
**확정하지 않는 미리보기**로 따로 흘린다(새 경로 `/app/room/{id}/preview` → 순번 없는 공개 이벤트 `AIM_PREVIEW`, 늘 덮어쓰기).
이동 쪽은 요트가 넣은 "차례 전체 마감"(D-183)을 그대로 써서 **포트 변경 0**, 미리보기 쪽만 포트에 **기본 구현이 있는 선택 메서드 1개**
(`preview`)를 더한다 — 다른 여섯 게임은 한 줄도 안 바뀐다.

## 1. 사용자 결정 (2026-11-11, AskUserQuestion)

| 질문 | 고른 답 | 뜻 |
| --- | --- | --- |
| 남에게 무엇까지 보여 줄까 | **위치·포신 각도·탄** | 힘 게이지는 숨긴다(발사 순간의 긴장 — 힘은 궤적으로만 드러난다) |
| 초당 몇 번 보낼까 | **초당 4번(250ms)** | 요트 홀드 묶음과 같은 간격. 받는 쪽은 사이를 보간한다 |
| 보이는 이동을 되돌릴 수 있나 | **걸으면 바로 확정** | 포트리스처럼 걸은 만큼 연료가 나간다. [이동 되돌리기] 버튼은 없어진다 |

세 번째 답 때문에 이동은 "미리보기"가 아니라 **서버 상태를 바꾸는 액션**이 된다(§2 후보 C). 각도·탄은 확정할 이유가 없어(쏘기 전까지
마음대로 바꾼다) 미리보기로 남는다.

## 2. 방법 후보 비교

| 후보 | 이동 | 조준 | 포트 | 장점 | 문제 |
| --- | --- | --- | --- | --- | --- |
| A. 전부 미리보기 | 미리보기(비확정) — `FIRE.moveDx` 그대로 | 미리보기 | +1 | 룰·엔진·봇·골든 무변경 | **사용자 결정(걸으면 확정)과 다르다** |
| B. 전부 엔진 액션 | `MOVE` | `AIM` 도 액션(상태에 저장) | 0 | resync 가 조준까지 맞춘다 | 각도 끌기마다 락·상태 저장·순번 소비·턴 시계 재무장. 초당 8번(이동 4 + 조준 4)이 `game-action` 버킷(10초 30번)을 넘어 **`FIRE` 가 레이트리밋에 버려질 수 있다**. 같은 세션의 프레임이 동시에 처리돼 `AIM` 이 `MOVE`·`FIRE` 를 `BUSY` 로 밀어낸다 |
| **C. 이동 = 액션, 조준 = 미리보기 (채택)** | `MOVE`(확정) | 미리보기 | +1(조준만) | 위치는 늘 서버 권위값, resync 가 이동을 맞춘다. 조준 프레임은 락·저장·순번·턴 시계와 무관 | 엔진에 `MOVE`·차례 전체 마감이 들어간다(§4) |
| D. 조준 = 인프라 불투명 중계 | (C 와 같음) | 리액션처럼 내용 모르는 JSON 중계 | 0 | 포트 무변경 | 서버가 내용을 검증하지 못한다(차례 아닌 사람 프레임·범위 밖 각도·없는 탄·숨기기로 한 힘까지 그대로 퍼진다) — "클라 값은 검증 대상"(절대 원칙) 위반. 차례 아닌 좌석의 프레임도 모두에게 간다 |

- C 와 D 의 차이는 조준 미리보기를 **게임이 걸러 주느냐**뿐이다. 포트 메서드 1개(기본 구현 있음)로 서버 권위를 지키는 쪽을 고른다(D-229).
  D 는 "다른 게임도 쓰는 범용 커서"가 필요해지면 다시 볼 수 있다 — 지금 그런 게임은 없다.

## 3. 전체 흐름

```
차례 주인(클라)                          서버                                   다른 사람·관전자
 ◀ 꾹 누름 → 로컬로 40ms마다 1걸음 예측
 250ms마다 MOVE(dx=그동안 걸음) ──────▶ /action: 락 → 걷기(Walker) → 저장
   (보낸 MOVE 응답 전엔 다음 MOVE 안 보냄)   → UNIT_MOVED(순번) ───────────────▶ path 를 250ms 동안 따라 걷는다
 ◀ UNIT_MOVED(내 좌석) = 응답, 서버 위치로 맞춤
 포신 끌기·탄 고르기
 250ms마다(바뀌었을 때만) + 2초마다 유지 ─▶ /preview: 락 없음 → 엔진 preview 가 검사·정리
   PREVIEW(angle, shell, n)                  → AIM_PREVIEW(순번 없음) ─────────▶ 포신 각도 보간, 탄 칩 표시
 [발사] — 응답 안 온 MOVE 가 있으면 기다렸다가
 FIRE(moveDx=아직 안 보낸 걸음, …) ─────▶ /action: 지금처럼 해소 → SHOT_RESOLVED ─▶ 재생
```

## 4. 이동 확정 — 서버 (포트 변경 0, D-228)

### 4.1 새 액션 `MOVE`

| 필드 | 범위 | 뜻 |
| --- | --- | --- |
| `dx` | −130~130, 0 아님 | 부호 방향으로 `|dx|` 걸음. 실제 걸음은 남은 연료·벽이 정한다(룰 §5.1 그대로) |

- 검증: `AIMING` · 내 차례 · 범위. 거절은 지금 코드 그대로(`NOT_YOUR_TURN`·`MATCH_OVER`·`OUT_OF_RANGE`) + **`CANNOT_MOVE`**(연료 0 이거나 첫 걸음이 벽·경계라
  한 걸음도 못 감 — 상태 무변경. 빈 `UNIT_MOVED` 로 순번을 쓰지 않으려고).
- 해소: 지금 `FIRE` 안의 "이동" 단계와 **같은 `Walker`** 를 부른다. 걸음마다 발 칸 경로, 남은 연료, 떨어짐을 상태에 반영한다.
- 이벤트 `UNIT_MOVED`(공개, **순번 있음** — 공개 상태가 바뀌므로 D-126): `{ seat, turn, path:[x,y,…], toX, toY, fuelLeft, fall | null, hpAfter, eliminated | null }`.
  결과값 원칙(원카드 §4.3) — 끝 위치·연료·체력이 들어 있어 하나 빠져도 다음 것이 맞춘다.
- **떨어짐**: 치명적이지 않은 낙하면 피해를 반영하고 차례가 계속된다(그 자리에서 더 걷거나 쏠 수 있다). 구름 바다·체력 0 이면 지금 `FIRE` 이동 중 사망과
  같이 차례가 끝난다 — 탈락 처리 → 매치 종료 판정 → 아니면 `RESOLVING`(대기 = 걸음 × 2틱 + 떨어진 칸, 하한 600ms) → 다음 차례.
- 봇은 쓰지 않는다 — 봇은 지금처럼 `FIRE.moveDx` 로 걷고 쏜다(봇 정책·평가·골든 무변경).

### 4.2 `FIRE.moveDx` 는 남긴다

- 뜻이 "이 자리에서 더 걷고 쏜다"가 된다. 봇·지금 클라·테스트가 그대로 돌고, 새 클라는 **아직 안 보낸 걸음**을 여기에 실어 마지막 MOVE 를 아낀다.
- 같은 차례에 `MOVE` 로 쓴 연료 + `FIRE.moveDx` 걸음이 남은 연료 하나를 나눠 쓴다 — 상태의 `fuel` 이 이미 줄어 있으므로 따로 셀 것이 없다.
- **같음 검사**(S6a 핵심 테스트): 떨어짐이 없는 한 `MOVE(a) → MOVE(b) → FIRE(moveDx=c)` 와 `FIRE(moveDx=a+b+c)` 의 도착점·남은 연료·딜레이·대본의
  탄도 부분이 같다(무작위 시드 표본). 대본 `move` 는 FIRE 안에서 걸은 부분만 싣는다.

### 4.3 차례 전체 마감 — 엔진 타이머 (D-183 그대로)

- 인프라는 성공한 액션마다 턴 데드라인을 처음부터 다시 건다(`onTurnAdvanced`) — `MOVE` 가 생기면 걸을 때마다 시간이 다시 찬다.
- 요트와 같이 **차례 시작 시각을 상태에 두고**(`turnStartedAt`, 새 필드) `timer(state)` 가 `AIMING` 에서 `turnStartedAt + GameContext.turnSeconds − 지금`을
  돌려준다. 이미 쓰는 `PICKING`(고르기 마감)·`RESOLVING`(연출 대기) 타이머 옆에 한 갈래가 는다. 만기면 `onTimer` = 지금의 시간 초과(`SKIP`)와 같은 전이.
- 인프라 턴 데드라인(마지막 액션 + T)은 늘 더 늦어 폴백으로만 남는다. `MOVE` 가 없는 판(봇·옛 클라)은 둘이 같은 시각이라 동작이 그대로다.
- resync 바깥 `turnRemainingMs`(인프라 — 마지막 액션 기준)는 MOVE 뒤엔 틀린다. 요트처럼 **공개 뷰에 엔진이 계산한 `turnRemainingMs`** 를 싣고 클라는 그것을 쓴다.
- 옛 JSON(필드 없음 — 배포 전에 시작해 Redis 에 남은 판): `turnStartedAt` 0 → AIMING 타이머 없음(지금과 같음). 그 판에서 새 클라가 `MOVE` 를 보내면 첫 `MOVE` 가
  `turnStartedAt` 을 지금으로 채운다 — 늘어나는 시간은 많아야 한 번의 T.

### 4.4 룰이 바뀌는 곳 (S6a 에서 `docs/rules-artillery.md` 를 고친다)

| 곳 | 지금 | S6 |
| --- | --- | --- |
| §4.1-2 "차례당 액션은 하나" | 이동은 `FIRE` 에 싣는다 | 차례 주인은 `MOVE` 를 여러 번 보낸 뒤 `FIRE`·`SKIP` 중 하나로 끝낸다(`FIRE.moveDx` 도 유효) |
| §4.2 넘기기·시간 초과 딜레이 | 80 | **80 + 이동 딜레이**(쓴 연료 2칸당 1) — 걸어 놓고 넘겨 딜레이를 아끼는 길을 막는다. 걷지 않았으면 80 그대로 *(설계자 판단)* |
| §6.4 물뿌리개 | 걷기 전에 체력 +25 | `FIRE` 를 해소할 때(이미 `MOVE` 로 걸은 뒤, `FIRE.moveDx` 걷기 전에) — 아이템은 계속 `FIRE` 에만 싣는다 *(설계자 판단: MOVE 에 아이템을 싣지 않아 액션을 단순히)* |
| §5.1 끝 "그 차례는 거기서 끝난다" | `FIRE` 안 이동 | `MOVE` 도 같다(§4.1) |
| §12 불변식 | — | `AIMING` 이면 `turnStartedAt > 0`(새 판), 연료는 차례 안에서 줄기만 한다 |

- 물리(탄도·폭발·낙하·지형)는 한 비트도 안 바뀐다. `MOVE` 를 쓰지 않는 판은 상태 필드 하나(`turnStartedAt`) 말고는 지금과 같아야 한다 —
  `ArtilleryGoldenTest.physics_fingerprint_unchanged_since_s4` 가 그대로 통과해야 한다(JSON 해시는 필드 추가로 다시 고정).

## 5. 조준 미리보기 — 포트 선택 확장 1개 (D-229)

### 5.1 포트

```java
// ⑧ 미리보기 (D-229) — 기본 없음. 상태를 바꾸지 않는 실시간 표시용.
default Optional<GameEvent> preview(GameState state, int seat, GameAction input) { return Optional.empty(); }
```

계약:

1. **순수**: 상태를 바꾸지 않고 저장소를 건드리지 않는다. 락 밖에서 불리므로 같은 순간의 액션과 겹칠 수 있다(낡은 상태를 볼 수 있다).
2. **가볍다**: 차례 주인 한 명이 초당 수 번 부른다(씨앗포 4번 + 유지). 지형 재구성 정도(≤ 1ms)까지만.
3. 돌려주는 이벤트는 **공개·순번 없음**(`privateSeat() < 0`, `sequenced() == false`). 어기면 인프라가 WARN 로그 후 버린다(순번 구멍·비공개 누출 방지).
4. 받아들일 수 없는 입력(차례 아님·단계 아님·범위 밖·이 게임의 미리보기 타입이 아님)은 **empty** — 거절 `ERROR` 를 보내지 않는다(초당 수 번 오는 프레임에 에러 폭주 방지).
5. 입력은 `actionType()` 으로 역직렬화한다(새 역직렬화 seam 없음). 미리보기 전용 타입은 `apply` 가 `INVALID_ACTION` 으로 거절하고 `legalActions`·봇에는 나오지 않는다.

**다른 게임 영향 0 의 근거**

- 기본 구현이 `Optional.empty()` 라 티츄·스컬킹·원카드·요트·오목·구름항로는 한 줄도 안 바꾼다(D-128 ⑦ 엔진 타이머와 같은 옵트인).
- 기존 `/action` 경로·브로드캐스터·스케줄러·봇 루프는 그대로다 — 미리보기는 새 컨트롤러만 지난다.
- 확인: ① `GameEngine` 기본 `preview` 가 empty 인 단위 테스트, ② 여섯 게임 어댑터에 `preview` override 0건(grep), ③ 미리보기 컨트롤러가
  empty 면 아무것도 방송하지 않는 테스트, ④ `grep -rniE 'artillery|씨앗포' server/src/main/java/com/mirboard/infra` → 0건 유지, ⑤ 서버 전체 스위트 회귀 0.

### 5.2 인프라 — 게임 중립 새 컨트롤러

`infra.ws.GamePreviewController` — `@MessageMapping("/room/{roomId}/preview")`:

1. 방 조회 → `IN_GAME` 이 아니거나 보낸 사람이 좌석에 없으면 **조용히 버린다**(관전자 포함).
2. `GameEngineProvider.forRoom(room)` → `actionType()` 으로 역직렬화(실패면 버림) → `loadState()`(**락 없음**, Redis GET 1회).
3. `engine.preview(state, seat, input)` → 있으면 계약 3 확인 후 `GameEventBroadcaster.broadcast` — 순번 없는 공개 이벤트라 `/topic/room/{id}` 로 seq 없이 나간다.
4. **하지 않는 것**: 락·저장·`onTurnAdvanced`·봇 스케줄·메트릭 거절 카운트. 그래서 미리보기는 `BUSY` 를 만들지도 받지도 않고 턴 시계를 건드리지 않는다.

- 같은 토픽을 쓰는 이유: 클라 훅은 순번 없는 공개 이벤트를 판정 없이 sink 로 넘긴다(`judgeSeq` → `unsequenced`, D-124) — 훅·구독 변경 0. 다른 게임 sink 에는 이 type 이 오지 않는다(그 게임 엔진이 만들지 않음).

### 5.3 씨앗포 미리보기

- 입력 `PREVIEW { angle, shell, n }` (`ArtilleryAction.Preview` — `apply` 는 `INVALID_ACTION`).
- `preview` 가 내보내는 것 — 사용자 결정 "위치·포신 각도·탄":
  `AIM_PREVIEW { seat, turn, angle, shell | null, n }`
  - `AIMING` 이고 `seat` 가 차례 주인일 때만. `angle` 0~180 밖이면 empty.
  - `shell` 은 그 포대가 **지금 쏠 수 있는 탄**일 때만 싣고 아니면 null(없는 특수탄으로 허세 못 부리게).
  - **힘은 받지도 내보내지도 않는다** — 수정된 클라가 실어 보내도 서버가 버린다(입력 타입에 필드가 없다).
  - 위치는 싣지 않는다 — 위치는 `UNIT_MOVED` 가 확정값으로 준다.
  - `turn`(차례 번호)·`n`(그 차례 안에서 보낸 쪽이 1씩 올리는 번호)은 받는 쪽이 **낡은 프레임을 버리는** 기준이다(§6.1).
- 비용: 상태 역직렬화 + 비교 몇 개 — 지형 재구성도 없다.

## 6. 다른 장치와의 관계

### 6.1 순번(D-126)·순서

- `UNIT_MOVED` 는 순번 있음(상태 변경), `AIM_PREVIEW` 는 순번 없음(상태 무변경 — 빠져도 다음 프레임이 덮는다). 미리보기가 방 순번을 쓰지 않으니
  구멍 resync 도 안 부른다.
- 서버는 같은 세션의 인바운드 프레임을 **순서대로 처리한다고 보장하지 않는다**(`preserveReceiveOrder` 미설정). 그래서:
  - `MOVE` 는 **한 번에 하나만 보낸다** — 내 좌석의 `UNIT_MOVED`(또는 `ERROR`)가 와야 다음 `MOVE`. 그 사이 걸음은 로컬에 모은다.
  - `FIRE` 도 응답 안 온 `MOVE` 가 있으면 그 응답 뒤에 보내고, 모은 걸음은 `FIRE.moveDx` 로 싣는다 → `FIRE` 가 `MOVE` 를 앞지르는 일이 없다.
  - `AIM_PREVIEW` 는 받는 쪽이 `(turn, n)` 이 지금 차례이고 지금까지 본 최대 `n` 보다 클 때만 쓴다. 지금 차례가 아니거나 재생 중이면 버린다(락 없이 읽은 낡은
    상태 때문에 `SHOT_RESOLVED` 뒤에 도착하는 미리보기도 여기서 걸러진다).

### 6.2 방 락·`BUSY`

- `MOVE` 는 다른 액션처럼 락을 잡는다(수 ms). 한 번에 하나만 보내므로 **자기 액션끼리** `BUSY` 가 나지 않는다.
- 남는 경합: 같은 순간의 엔진 타이머 발화(차례 마감)·탈주 확정. 이때 `MOVE` 가 `BUSY` 면 클라는 **한 번 다시 보낸다**(요트 `ROLL`·`SCORE` 와 같음), `FIRE` 도 같다.
- 미리보기는 락을 안 잡는다 — `MOVE`·`FIRE` 를 밀어내지 않는다.

### 6.3 레이트리밋

| 버킷 | 지금 | S6 | 이유 |
| --- | --- | --- | --- |
| `game-action` (`/action`) | 10초 30번 | **10초 60번** | 연료를 다 걷는 데 130걸음 × 40ms = 5.2초 → `MOVE` 최대 21번 + `FIRE`. 딜레이 차례로 같은 사람이 연달아 차례를 받으면 10초 안에 40번을 넘을 수 있다 — 넘으면 `FIRE` 가 버려진다. 다른 게임에는 느슨해질 뿐 |
| `game-preview` (`/preview`, 새로) | — | **10초 60번** | 초당 4번 + 2초 유지 = 4.5번. 넘친 프레임은 조용히 버린다(`notifyIfAction` 은 `/action` 만) |

- 둘 다 설정값(`mirboard.ratelimit.buckets.*`)이고 `StompRateLimitInterceptor.bucketFor` 에 경로 한 줄이 는다 — 게임 이름 없음.

### 6.4 대역폭·서버 부담 (무료 사양 기준)

| 항목 | 크기 | 빈도(걷거나 조준하는 동안) | 방 하나(구독 6명) |
| --- | --- | --- | --- |
| `UNIT_MOVED` | ≈ 250B (경로 ≈ 6점) | 초당 4 | ≈ 6KB/s 내보냄 |
| `AIM_PREVIEW` | ≈ 150B | 초당 4 + 유지 | ≈ 4KB/s 내보냄 |
| `MOVE` 처리 | 락·상태 GET/SET·순번·방송·재무장 ≈ Redis 10회, CPU ≤ 0.1ms(걷기 + 지형 재구성 — S1 실측 한 발 0.064ms) | 초당 4 | Redis 초당 ≈ 40회 |
| 미리보기 처리 | Redis GET 2회(방·상태) | 초당 4.5 | Redis 초당 ≈ 9회 |

- 차례 주인 한 명만 보내고 차례 밖에서는 0 이다. 상시 도는 루프는 여전히 없다. 동시 20방이 모두 걷는 최악에도 CPU 1% 아래, Redis 초당 1,000회 남짓.
- 휴대폰 받는 쪽 ≈ 10KB/s 는 걷는 몇 초 동안뿐이다.

### 6.5 재접속·resync

- 이동은 상태에 있으니 resync 가 **확정 위치·남은 연료**를 그대로 준다(지금 "발사 전 이동은 남에게 안 보임"이 사라진다).
- 조준은 상태에 없다 — resync 직후엔 포신이 기본(마지막으로 쏜 각도)으로 보이다가 다음 미리보기에서 맞춰진다. 그래서 보내는 쪽은 바뀌지 않아도 **2초마다 한 번** 다시 보낸다.
  *(설계자 판단: 상태에 넣으면 B 의 문제가 돌아온다.)*
- 차례 주인 자신이 재접속하면 resync 의 위치·연료에서 다시 걷는다. 로컬에 모아 두고 못 보낸 걸음은 버린다.
- 남은 시간은 공개 뷰의 엔진 `turnRemainingMs`(§4.3).

### 6.6 봇

- 봇 정책·`legalActions`·`timeoutAction` 무변경. 봇은 `MOVE`·미리보기를 쓰지 않으니 봇 차례는 지금처럼 대본 앞부분에서 걷는다.
- 봇 루프는 `pendingSeats`(AIMING = [차례 좌석])를 그대로 본다. 사람 차례에 `MOVE` 가 와도 봇 스케줄은 할 일이 없다.
- *(후속 후보, S6 밖)* 봇 차례에도 쏘기 직전 포신을 돌리는 연출 — 클라가 `SHOT_RESOLVED` 의 각도로 재생 앞에 넣을 수 있다(서버 무관).

### 6.7 딜레이 차례(D-226)

- 이동 딜레이는 지금처럼 차례가 끝날 때 "포대 연료 − 남은 연료"로 센다 — `MOVE` 가 연료를 줄여 두므로 공식이 그대로다.
- 넘기기·시간 초과에도 이동 딜레이를 더한다(§4.4).
- 클라는 차례 주인의 `fuelLeft`(`UNIT_MOVED`)와 미리보기 `shell` 로 **남의 "이번 차례 +N" 예상**도 보일 수 있다(지금은 내 차례만) — 숫자는 서버 딜레이 표로 계산한다.

### 6.8 탈주·관전

- 차례 중 탈주는 지금처럼 탈락 → 다음 차례. `turnStartedAt` 은 다음 차례 시작에서 새로 쓴다.
- 관전자는 같은 토픽이라 이동·조준을 똑같이 본다. 관전자의 `/preview` 프레임은 좌석이 없어 버려진다.

## 7. 단계와 테스트 기준

| 단계 | 범위 | 선행 | 검증 |
| --- | --- | --- | --- |
| **S6a 서버·포트** | ① 포트 `preview` 기본 메서드 + `docs/game-port.md` 계약 확정 ② `GamePreviewController` + `game-preview` 버킷 + `game-action` 60 ③ 씨앗포 `MOVE`·`turnStartedAt`·AIMING 타이머·`CANNOT_MOVE`·`UNIT_MOVED` ④ `Preview` 입력·`AIM_PREVIEW` ⑤ 공개 뷰 `turnRemainingMs` ⑥ 룰·STOMP 문서 | origin/main | 아래 서버 표 전부, 인프라 grep 0건, 서버 전체 스위트 회귀 0 |
| **S6b 클라** | 걷기 로컬 예측 + 250ms `MOVE` 한 번에 하나 + 응답으로 맞춤, [이동 되돌리기] 제거, `FIRE` 는 응답 뒤·남은 걸음 실음, 남의 `UNIT_MOVED` 경로 걷기 재생, `AIM_PREVIEW` 포신 보간·탄 칩, 낡은 프레임 거르기, 2초 유지, 엔진 남은 시간, 남의 "+N" 예상, 튜토리얼 문구 | S6a + **스킨 층(D-227) main 병합** | 아래 클라 표, 브라우저 두 창 실측(데스크톱·휴대폰 세로), e2e 1건 |

- S6a 만 배포돼도 안전하다: 지금 클라는 `MOVE`·`PREVIEW` 를 보내지 않고, 늘어난 것은 AIMING 엔진 타이머(인프라 시간 초과와 같은 시각·같은 `SKIP`)뿐이다.
- S6b 는 그리기(포대 위치 보간·포신 각도)를 스킨 층의 그리기 함수 위에 얹는다 — D-227 이 들어오기 전에 `draw.ts`·`ArtilleryField.tsx` 를 건드리면 충돌한다.

### S6a 서버 테스트

| 무엇 | 기준 | 위치(예정) |
| --- | --- | --- |
| 같음 검사 | 떨어짐 없는 무작위 시드 200개에서 `MOVE` 나눠 걷기 + `FIRE` ≡ `FIRE(moveDx 합)` — 도착점·연료·딜레이·탄도 | `ArtilleryMoveTest` |
| `MOVE` 거절 | 차례 아님·`RESOLVING`·`PICKING`·범위 밖·0·`CANNOT_MOVE`(연료 0·벽) 모두 상태 무변경 | `ArtilleryMoveTest` |
| `MOVE` 중 낙하 | 비치명 → 차례 계속·체력 반영 / 치명 → 탈락·RESOLVING·다음 차례 / 생존 ≤1 → 종료 | `ArtilleryMoveTest` |
| 차례 전체 마감 | 가짜 시계로 `MOVE` 를 거듭해도 `timer` 가 차례 시작 + T 로 줄어든다, 만기 `onTimer` = SKIP + 이동 딜레이 | `ArtilleryGameEngineTimerTest` |
| 옛 JSON | `turnStartedAt` 없는 상태: 타이머 없음, 첫 `MOVE` 가 채움 | `ArtilleryStateCompatTest` |
| 골든 | 물리 지문 무변경(`physics_fingerprint_unchanged_since_s4`), JSON 해시 재고정 | `ArtilleryGoldenTest` |
| 시뮬레이션 | 무작위 판에 `MOVE` 조각을 섞어 2~6인 1,000판 전부 상한 안 종료·불변식 위반 0 | `ArtillerySimulationTest` |
| 미리보기 정리 | 차례 아님·단계 아님·각도 밖 → empty, 없는 탄 → `shell` null, 힘 필드 없음, `sequenced()` false·공개 | `ArtilleryPreviewTest` |
| 포트 기본값 | 기본 `preview` empty, 여섯 게임 override 0건 | `GameEnginePreviewDefaultTest` + grep |
| 컨트롤러 | 대기실·좌석 없음·관전자·역직렬화 실패·empty → 방송 0, 계약 3 위반 이벤트 → WARN·방송 0, 정상 → seq 없는 공개 1건, 락·`onTurnAdvanced` 호출 0 | `GamePreviewControllerTest`(Mockito) |
| 레이트리밋 | `bucketFor("/app/room/x/preview") = game-preview`, 기본값 표 | `StompRateLimitInterceptorTest` |
| 서버 경로 IT | STOMP 두 클라: `MOVE`×3 → 상대가 `UNIT_MOVED` 3건(순번 연속), 중간 resync 가 위치·연료를 맞춤, `PREVIEW` → 상대에 seq 없는 `AIM_PREVIEW`, 이어서 `FIRE` 정상 | `ArtilleryLiveMoveIT`(Docker) |

### S6b 클라 테스트

| 무엇 | 기준 |
| --- | --- |
| 보내기 조절 | 꾹 누르기 2초 → `MOVE` ≤ 9건, 응답 전엔 다음 `MOVE` 없음, `FIRE` 가 대기 중 `MOVE` 를 앞지르지 않음, 남은 걸음이 `FIRE.moveDx` 에 실림 |
| 맞추기 | 서버 `toX` 가 예측과 다르면 서버 값 + 아직 안 보낸 걸음으로 다시 예측 |
| 남의 이동·조준 | `UNIT_MOVED` 경로를 250ms 동안 재생, `AIM_PREVIEW` 각도 보간, 낡은 `(turn, n)` 버림, 재생 중 버림 |
| sink | 새 type 둘이 `'unhandled'` 로 resync 를 부르지 않음 |
| 남은 시간 | `MOVE` 뒤에도 카운트다운이 차례 시작 기준 |
| 브라우저 | 두 창(+ 관전 창): 걷기·포신이 끊김 없이 보임, 네트워크 패널에서 보내는 프레임 초당 ≤ 4.5(미리보기)·≤ 4(이동), 휴대폰 세로 375px |

## 8. 하지 않는 것

- 힘 게이지 공유(사용자 결정), 궤적 미리보기(서버 권위 원칙 §4.1 — 그대로 없음).
- 서버 실시간 물리 루프 — `MOVE` 도 액션 한 번에 한 번 계산이다.
- 이동 되돌리기(사용자 결정).
- 범용 "커서 공유" 인프라(후보 D) — 두 번째 게임이 원하면 그때.
- 봇의 실시간 걷기·조준 송출.

## 9. 설계자 판단 목록 (검토 때 확인할 것)

1. 넘기기·시간 초과에도 이동 딜레이를 더한다(§4.4).
2. 물뿌리개는 `FIRE` 해소 때 — `MOVE` 로 걷다 떨어지는 피해는 막지 못한다(§4.4).
3. 한 걸음도 못 가는 `MOVE` 는 `CANNOT_MOVE` 거절(빈 이벤트로 순번을 쓰지 않음).
4. 조준은 상태에 넣지 않고 2초 유지 프레임으로 resync 뒤를 메운다(§6.5).
5. `game-action` 버킷을 10초 30 → 60 으로(모든 게임에 느슨해짐, §6.3).
6. 미리보기 입력은 `actionType()` 의 한 갈래로 역직렬화한다(새 seam 없음, §5.1-5).

## 10. S6a 구현 결과 (D-250)

설계대로 넣었고, 구체화한 것만 적는다(이유는 D-250).

- 연료는 상태의 **`moveFuelUsed`**(이번 차례에 `MOVE` 로 쓴 양)다 — §4.2 의 "상태의 `fuel`". 공개 뷰 `fuel` 은 남은 연료(포대 연료 − 쓴 양).
- 시계 없이 연 판(테스트 진입점)은 `turnStartedAt` 0 → 엔진 마감 없음. §4.4 불변식은 "AIMING 밖이면 0, 쓴 연료 0~포대 연료"로 넣고 `> 0` 은 IT 로.
- `MOVE` 로 탈락하면 `UNIT_MOVED.eliminated` 로만 알린다(별도 `UNIT_ELIMINATED` 없음). 연출 대기 `lastShot` 은 null — **S6b 클라는 대본 없는
  `RESOLVING` 을 처리해야 한다**(재생 없이 대기만).
- `PREVIEW` 입력은 모르는 필드 무시(힘을 실은 프레임도 각도·탄은 쓴다). 서버는 `/preview` 를 락 없이 처리하므로 같은 순간의 `MOVE` 와 겹친다 — 테스트
  IT 에서 첫 `MOVE` 가 공개 직후 `BUSY` 를 받는 것을 봤다(앞 전이가 락을 쥔 채 방송) — §6.2 의 "`BUSY` 면 한 번 다시"가 실제로 필요하다.
- 테스트: `ArtilleryMoveTest`·`ArtilleryTurnClockTest`·`ArtilleryPreviewTest`·`core/GameEnginePreviewDefaultTest`·`infra.ws.GamePreviewControllerTest`·
  `RateLimitRoutingTest` 추가 2건·`ArtilleryGoldenTest`(두 필드를 빼면 S5 바이트 그대로)·`ArtillerySimulationTest`(MOVE 조각)·`infra.ws.ArtilleryLiveMoveIT`(Docker).

## 11. S6b 클라 구현 결과 (D-251)

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| 보내기 계획(조각 모으기·한 번에 하나·BUSY 한 번·FIRE 순서)·예측·따라가기·포신 보간·미리보기 간격 | `liveMove.ts` | `liveMove.test.ts`(계획 8·따라가기 3) |
| 소켓 붙이기(응답·거절·resync 를 스토어 구독으로 같은 순간에) | `useLiveMove.ts` | `ArtilleryTable.test.tsx`(박자·답 대기·FIRE 순서·BUSY·낭떠러지) |
| 조준 미리보기 보내기(바뀌면 250ms, 유지 2초, 힘 없음) | `useAimPreviewSender.ts`, 게임 중립 `ws/useStompRoom.sendPreview` | `ArtilleryTable.test.tsx` |
| `UNIT_MOVED`·`AIM_PREVIEW`·엔진 남은 시간·남은 연료·MOVE 탈락 대기 | `artilleryStore.ts`, sink `CANNOT_MOVE`·답 대기 중 조용한 거절 | `liveMove.test.ts`(스토어 8) |
| 남의 걸음·포신 그리기 | `ArtilleryField.tsx`(`follow`·`liveAim`, 따라가는 동안만 매 프레임) | 브라우저 두 탭 |
| [제자리]·유령 제거, 탄 칩, 튜토리얼 문구 | `ArtilleryControls.tsx`·`ArtilleryTable.tsx`·`23-artillery-table.css`(`.ar-aiming`·`.ar-aim-shell`) | `ArtilleryTable.test.tsx` |

브라우저 실측(로컬 서버, 두 탭 = 두 게스트): 2초 꾹 누르기 → `MOVE` 8번 / 2.2초(초당 ≈ 3.6, 늘 하나만 대기), 각도를 바꾸는 동안 `PREVIEW` 250ms 간격 +
2초 유지, 힘 필드 없음. 상대 탭 캔버스에서 걷는 포대 중심을 200ms 마다 재면 4초 동안 고르게 이동(465 → 739px)하고 멈춘 뒤 그대로, 포신·탄 칩도 따라 바뀜.
걷기 응답 뒤 [발사]가 남은 걸음 3칸을 `moveDx` 로 실음. 시간 초과 넘김에 이동 딜레이가 붙음(⏱ +129 = 80 + 연료 100/2 − 상대 1). 375px 가로 넘침 없음.
처음 실측에서 꾹 누르기가 낭떠러지를 넘어 실제로 떨어져(서버가 확정하니까) 판이 끝났다 — 낡은 클로저 회귀를 고치고 회귀 테스트를 넣었다(D-251).

- 하지 않은 것: 남의 "이번 차례 +N" 예상(§6.7 — 선택), 봇 차례 포신 돌리기 연출(§6.6 후속 후보).
