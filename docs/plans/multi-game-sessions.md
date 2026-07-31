# 멀티게임 — 세션 분할 실행 계획

> 작성 2026-07-30 · 설계 근거: `docs/game-port.md`(D-97) · `docs/plans/multi-game.md`
> 각 세션은 **독립적으로 시작 가능**하도록 썼다. 이 파일과 §의 "읽을 것"만 열면 된다.

## 사용법

1. 새 세션을 열고 `/clear`
2. 아래 세션 블록 하나를 골라 **"시작 프롬프트"를 그대로 붙여넣기**
3. 세션 끝에 Phase Gate(변경 요약 + 다음 진입 동의)

**순서 의존을 무시하지 마라.** S5 는 S2 의 인원 가변이 있어야 스컬킹 4인 방을 만들 수
있다(없으면 항상 8인 방이 된다). 각 블록의 "선행" 참조.

*정정 (D-99)*: 원래 S2 의 선행을 S1 로 적었으나 **실제 의존이 없었다** — S2 가 만지는
파일(`RoomService`·`RoomController`·클라 모달)과 S1 이 만지는 파일(`GameEngine`·
`GameStompController`·스케줄러)의 교집합이 0 이고 §3 이 포트 표면을 참조하지 않는다.
그래서 S2 를 S1 보다 먼저 완료했다.

*추가 (D-98)*: S1 도 완료. 다만 **교집합 0 은 사실이 아니었다** — S1 이 `RoomController` 의
resync/rematch 를 포트로 옮겨야 했다(S2 는 같은 파일의 create 를 만졌다). 두 세션이 같은
워킹 트리에서 동시에 돌아 실제로 충돌 위험이 있었고, S1 은 별도 git worktree 로 격리해
진행했다. **같은 계열 세션을 병행할 땐 worktree 로 분리할 것.**

## 전체 지도

| 세션 | 범위 | 선행 | D 번호 | 코드 변경 | 병렬 |
| --- | --- | --- | --- | --- | --- |
| ~~S0~~ | 포트 설계 | — | D-97 | 0 (문서) | ✅ **완료** |
| ~~S1~~ | 포트 추출 (티츄를 포트 뒤로) | S0 | D-98 | 서버 | ✅ **완료** |
| ~~S2~~ | 인원 가변 (계약 변경) | — | D-99 | 서버+클라+docs | ✅ **완료** |
| ~~S3~~ | 스컬킹 룰 명세 | — | D-100 | 0 (문서) | ✅ **완료** |
| ~~S4~~ | 스컬킹 도메인 (카드·트릭·입찰·점수) | S1·S3 | D-101 | 서버(신규 패키지) | ✅ **완료** — 순수 엔진 275건 |
| **S5** | 스컬킹 통합 (엔진·봇·디스패치) | S2·S4 | D-102 | 서버 | ❌ 단독 |
| **S6** | 스컬킹 클라 | S5 | D-103 | 클라 | ❌ 클라 1트랙 |

**병렬 가능 조합**: S3 는 언제든(문서 전용). S4 는 신규 패키지라 병행 가능.
그 외는 공유 파일(`GameStompController`·`RoomService`·`styles/parts`)이 겹쳐 단독.
**병행할 땐 git worktree 로 트리를 분리**한다 — 같은 워킹 트리에서 두 세션이 돌면 커밋 안 된
편집을 서로 덮어쓴다(S1·S2 에서 실제로 발생).

## 공통 규약 (모든 세션)

- 착수 전 `docs/decisions.md` 에 **D-NN 스텁 선행 커밋** (CLAUDE.md Phase Gate)
- 작업 브랜치 사용, 단계별 독립 커밋
- 검증: `./scripts/check.sh fast`(커밋 게이트) → 서버 작업은 `check.sh server`
- 계약 변경은 **서버 DTO + 클라 미러 + docs 를 한 커밋**으로 (parallel-tracks 직렬화 4)
- **티츄 룰을 손대지 않는다** — 룰 변경이 섞이면 회귀 원인 분리가 불가능해진다

---

## ~~S1 — 포트 추출 (티츄를 포트 뒤로)~~ — ✅ 완료 (D-98)

**D-98**. 별도 git worktree 에서 진행(같은 트리에서 S2 세션이 동시 작업 중이었음).

### 한 것
`docs/game-port.md` §1 의 인터페이스를 실제로 만들고 티츄를 그 뒤로 옮겼다.

- `GameEngine`·`GameAction`·`GameEvent` 빈 마커 → 실제 표면 (+ `GameState`·
  `GameActionRejectedException` 신설, `GameContext` 에 targetScore/stake/botSeats 추가)
- `GameDefinition.newEngine()` 이 실제 디스패치에 쓰인다 — `GameEngineProvider.forRoom` 단일 경로
- `GameStompController` 하드타입 제거 → 방 → gameType → `engine.actionType()` 로 역직렬화
- `MatchProgressService`·`BotScheduler`·`TurnTimeoutScheduler`·`DesertionService`·
  `GameEventBroadcaster`·`RoomController`(resync/rematch) 전부 포트 뒤로
- 매치 종료/탈주 판정을 인프라 → 엔진으로 이동 (`advance` / `desert`)
- 티츄는 **2계층**: 순수 `TichuEngine` + 포트 어댑터 `TichuGameEngine`

### 결과
- **완료 기준 달성**: infra → `domain.game.tichu` 의존 **10파일 → 1파일**.
  남은 `RoomChipService` 의 이유는 `docs/game-port.md` §2 에 기록(칩은 포트 밖).
- **검증**: 서버 **412건 전량 그린** + `check.sh bot-stress 5`. 티츄 룰 코드 무변경.
- D-97 §1 설계에서 3곳을 고쳤다(`isMatchOver` 무인자 / `pendingSeats` 복수 /
  `initialState` 미채택) — 근거는 `docs/game-port.md` §1.
- 함정 기록: Spring Framework 7 브로커 컨버터는 Jackson **3** 기반이라 `@Payload` 대상으로
  Jackson 2 `JsonNode` 를 쓸 수 없다. payload 는 `Map<String,Object>` 로 받는다.

### S4/S5 에 넘기는 것
- 새 게임은 `GameEngine` 구현 + `GameDefinition @Component` + `GameStartingEvent` 리스너
  세 개면 인게임까지 자동 연결된다(스케줄러·브로드캐스터·resync 수정 불필요).
- `initialState` 가 포트에 없으므로 스컬킹도 라운드 시작은 `GameStartingEvent` 리스너로.

---

## ~~S2 — 인원 가변 (계약 변경)~~ — ✅ 완료 (D-99)

**선행**: 없었음(위 *정정* 참조). **D-99**. 계약 슬라이스 한 커밋으로 완료.

### 왜 필요했나
스컬킹이 **2~8인**이라 `capacity = def.maxPlayers()` 고정이면 항상 8인 방이 된다.

### 한 것
- `POST /api/rooms` 에 `capacity` 선택 필드 (미지정 시 `maxPlayers()` — 현행 호환)
- 검증 `minPlayers() <= capacity <= maxPlayers()`, 위반 시 `INVALID_CAPACITY`
  (`InvalidCapacityException` → `GlobalExceptionHandler`, details 에 허용 범위)
- `RoomService.createRoom` / `fillWithBots` 좌석 계산이 capacity 를 따름
- 클라 방 만들기 모달에 인원 선택 (`minPlayers < maxPlayers` 일 때만 노출, 기본값은
  서버 기본과 같은 `maxPlayers`). 고정 인원 게임은 `capacity` 를 **보내지 않음**
- `docs/api.md` · `implementation-status.md` · `redis-keys.md` · `game-port.md` §3 갱신

### 검증 결과
`RoomCapacityIntegrationTest` 8건(가변 게임은 테스트용 fake `GameDefinition` 2~8인) +
`CreateRoomModal.test.tsx` 5건 신규. 서버 399건·클라 144건 전량 그린 — 티츄 방 생성 IT
무변경 통과가 곧 **UI·동작 무변경**의 증거.

### 여기서 발견한 것 → S5 로 이월
시드 봇은 4명(V3)뿐이라 `capacity - 1 > 4` 인 방은 `fillWithBots` 가 실패한다. 티츄
4인에선 도달 불가였고 **스컬킹 6~8인 방에서 처음 문제가 된다** — S5 봇 정책에 봇 풀
확장을 포함할 것.

---

## S3 — 스컬킹 룰 명세 (문서 전용)

**선행**: 없음. **D-100**. **언제든 병행 가능**(코드 0).

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S3 를 진행해줘.
근거 정본은 볼트 `inbox/clippings/스컬킹.md` 다. 이걸 기준으로
docs/rules-skullking.md 를 써줘. docs/rules-tichu.md(D-56) 와 같은 형식으로.
`wiki/스컬킹 룰 구조.md` 는 같은 원문의 요약이라 보조로만 참고하고,
둘이 어긋나면 클리핑을 따른다.
```

### 근거 (사용자 지정)
**`inbox/clippings/스컬킹.md`** — 나무위키 원문 클리핑. 카드 장수·점수 수치가 그대로 있다.
`wiki/스컬킹 룰 구조.md` 는 이 원문을 요약한 파생 노트라 수치가 뭉개져 있다
(예: "검정 14는 배점 두 배" ← 원문은 10점/20점). **요약본과 원문이 다르면 원문이 맞다.**

### 왜 먼저 쓰는가
D-56 의 `rules-tichu.md` 가 "룰 코드 1:1 매핑의 단일 진실원"으로 잘 작동한 선례.
구현 중 룰 해석을 놓고 헤매는 비용이 문서 쓰는 비용보다 크다.

### 반드시 담을 것
- **인원 2~8**(BGG 추천 4~6), 10라운드, **라운드 N = N장 배분** → 그 라운드 트릭 수도 N
- **예측은 전원 동시 공개** — 순차 입력받되 전원 제출까지 공개를 미루는 처리가 필요
- 점수 (원문 §점수 계산 그대로 옮길 것):
  - 적중 `승수 × 20` + 보너스 / 실패 `|예측−실제| × 10` **감점**, 보너스 없음
  - **0트릭 예측은 별도 식** — 적중 `라운드 수 × 10`, **실패도 `라운드 수 × 10` 감점**.
    실패 시 `|예측−실제| × 10` 을 쓰면 안 된다(0예측 실패는 항상 라운드 수로 계산)
- **보너스는 예측 적중 시에만** 붙고 실패하면 전부 소멸. 수치까지 명시:
  - 노랑·보라·초록 14 = 각 10점 / **검정 14 = 20점**
  - 해적으로 잡은 인어 20점(장당) · 스컬킹으로 잡은 해적 30점(장당) · 인어로 스컬킹 40점
- 색상 카드 4색 × 1~14 — 초록(앵무새)·보라(지도)·노랑(보물상자) + **검정(해적기)만 으뜸패**.
  검정이 2장 이상이면 그중 숫자가 큰 쪽 승. 리드가 색상이면 follow 의무, 특수 카드는 면제
- 특수 카드 **비추이적 3자 순환**: 해적 > 인어 · 인어 > 스컬킹 · 스컬킹 > 해적.
  **셋이 한 트릭에 다 나오면 인어 승**(순환을 깨는 명시적 예외)
- 탈출(무조건 패배, **단 전원 탈출이면 먼저 낸 사람 승**), 티그리스(낼 때 해적/탈출 **선언 선택**)
- 동일 특수 카드 복수 시 **먼저 낸 사람** 승 (해적 2장 이상 / 인어 2장)
- **리드 수트 확정 규칙 — 두 갈래이고 여기가 함정이다**
  - 캐릭터 카드(인어·해적·스컬킹·해적선언 티그리스)로 리드 → 그 트릭엔 **리드 수트 없음**
  - **탈출**(·탈출선언 티그리스·약탈품)로 리드 → 리드 수트가 **확정되지 않고 다음 플레이어에게
    넘어간다**. 그 사람도 탈출이면 또 넘어간다(연쇄). 즉 `leadSuit = 첫 카드의 색`으로
    구현하면 틀린다 — **처음 나온 색상 카드**가 리드 수트다
- **범위 밖 명시**: 상급자 카드 3종 — 약탈품(가져간 사람과 동맹, 둘 다 적중 시 상호 20점) ·
  크라켄(트릭 전원 패배) · 흰고래(특수 카드 전부 탈출화 + 리드 수트 소멸).
  셋 다 **판정 자체를 흔들고** 크라켄·흰고래 동시 등장 시 나중에 낸 쪽 적용이라,
  기본 룰 안정화 후 별건으로
- **출처 신뢰도 주석**: 나무위키는 집단 편집이고 2021년판에서 최대 인원·상급자 카드
  구성이 바뀌었다. 퍼블리셔(Grandpa Beck's Games) 규칙서와 어긋날 수 있음을 문서에 남길 것

### 설계 힌트 (볼트 정리본의 구현 관점)
- **은닉 정보는 손패뿐** — 예측 승수·획득 트릭 수는 공개다(State Hiding 범위가 티츄보다 좁다)
- 트릭 승자 판정은 조건 분기를 늘리지 말고 **우선순위 테이블**로 푼다
- **손패 크기가 라운드마다 변한다** — 고정 크기 전제 자료구조는 후반 라운드에서 깨진다

---

## ~~S4~~ — 스컬킹 도메인 (신규 패키지) ✅ 완료 (D-101)

**선행**: S1(포트) ✅ · S3(명세) ✅. 신규 패키지라 충돌면 0 이었다 — 기존 파일 수정은
`scripts/check.sh`(rules 타깃에 스컬킹 추가) 한 줄과 docs 뿐.

### 한 것
1. `card/` — `SkullCard`·`SkullSuit`·`SpecialKind`·`TigressMode`·`Deck`(70장)
2. `state/` — sealed `SkullKingState`(Bidding/Playing/RoundEnd) · `PlayedCard`(티그리스
   해소) · `TrickState`(리드 수트 파생) · `TrickResult` · `PlayerState` · `SkullKingMatchState`
3. `trick/` — `LeadSuitResolver`(지연 확정) · `TrickResolver`(6단 사다리 테이블)
4. `action/` + `bid/` — sealed `SkullKingAction`(PlaceBid/PlayCard) · `ActionValidator` ·
   `BidRules`
5. `scoring/` — `RoundScorer` · `BonusCalculator` · `RoundScore`
6. `Dealer` · `SkullKingEngine`(순수) · `event/SkullKingEvent` · `invariant/`

**테스트 275건, Docker 불필요** (`./scripts/check.sh rules` 에 편입). 2~8인 전 좌석 수가
무작위 합법수만으로 10라운드 완주하며 매 액션 직후 불변식 통과. 서버 전체 687건 그린.

### 경계를 "순수 엔진까지"로 확정한 이유 (D-101)
원래 항목 5가 "`SkullKingEngine` — 포트 구현"이었는데, `GameEngine` 포트는
`loadState`/`saveState`/`actionType`/`advance`/`desert` 를 요구해 Redis·Spring 이 딸려 오고
**S5 범위("상태 저장/뷰 매퍼")와 겹친다**. 티츄가 순수 `TichuEngine` + 어댑터
`TichuGameEngine` 로 이미 푼 문제라 같은 선을 그었다. 덕분에 S4 산출물이 Docker 없이 전량
검증된다.

### 세 가지 설계 판단 (S5·S6 가 알아야 함)
- **티그리스 정체성을 `PlayedCard.kind()` 에서 1회 해소** — 강약·동점·보너스가 같은 값을
  읽으므로 명세 함정 #10("한 군데만 빠뜨리면 조용히 어긋난다")의 경로가 없다.
- **리드 수트를 저장하지 않고 파생** — 지연 확정(§6.1) 때문에 필드로 들면 stale 해진다.
- **불변식 기준이 70이 아니라 `handSize × seatCount`** — 8인 9·10 라운드는 6장을 안 쓴다.
- **중복 특수 카드에 copy 인덱스 없음** — 해적 5장은 값이 같다. 클라(S6)가 "몇 번째
  해적"을 왕복시킬 필요가 없다는 뜻이다.

---

## S5 — 스컬킹 통합

**선행**: S2(인원 가변) · S4(도메인). **D-102**. **단독 세션**.

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S5 를 진행해줘.
S4 의 SkullKingEngine 을 GameDefinition 으로 등록하고 인게임 디스패치에 연결해줘.
```

### 범위 — S4 가 남긴 것 정확히 (D-101 확인)
- **포트 어댑터** `SkullKingGameEngine implements GameEngine` — 순수 `SkullKingEngine` 을
  감싼다. 티츄의 `TichuGameEngine`(323줄)이 그대로 본보기다. 채워야 하는 포트 메서드:
  - `loadState`/`saveState` → Redis 스토어 (`SkullKingGameStateStore`)
  - `actionType()` → `SkullKingAction.class`
  - `publicView`/`privateView` → **뷰 매퍼 신규**. `privateView` 는 손패 + *제출 전* 본인
    예측만 (§5 경계). 전원 제출 후 예측·획득 승수는 `publicView`
  - `advance` → 순수 엔진의 `settleRound` + `startRoundAndDrain` 을 엮는다 — 탈주가 있는
    방은 **반드시 드레인 변형**(`applyAndDrain`/`startRoundAndDrain`)을 쓴다 (D-104 계약).
    잊으면 유령 차례에서 예외 없이 조용히 멈춘다 — 2-인자 불변식 체커가 그 상태를 잡는다
  - `isMatchOver` → `SkullKingMatchState.isMatchOver()` (매치 상태 스토어 필요)
  - `desert` → **D-104 로 확정·구현 완료** (유령 좌석 자동조종, "남은 사람끼리 계속").
    어댑터는 순수 `SkullKingEngine.desert(state, match, seat, humanSeats)` 를 부르고
    `Outcome`(NOT_APPLICABLE/CONTINUED/MATCH_ENDED)을 포트 boolean 에 매핑한다 — 포트
    javadoc 이 "매치를 강제 종료"라고 티츄 전제로 쓰여 있으므로 S5 에서 "탈주 처리 적용됨"
    으로 일반화할 것. `humanSeats` 는 방 점유자에서 봇을 뺀 좌석. **주의**: 탈주 확정 좌석의
    사람 액션은 SEAT_DESERTED 로 거절되므로 재접속 복귀는 관전자로만. 끊김 유예(120s)
    구간의 정지(탈주 확정 전이라 자동조종 미작동)는 미해결 — 스컬킹 방 turnSeconds 기본값
    또는 유예 중 임시 자동조종을 S5 에서 결정
- `SkullKingGameDefinition` `@Component` 등록 (id `SKULL_KING`, min 2 / max 8)
- 액션 역직렬화 seam 에 스컬킹 액션 등록 (`@JsonTypeInfo` 는 이미 붙어 있음)
- 봇 정책 — 순수 엔진의 `legalActions`/`timeoutAction` 이 이미 있으므로 배선만 하면 된다
  - ⚠ **봇 풀 확장 필수** (D-99 발견): 시드 봇 4명(V3)이라 `capacity - 1 > 4` 인
    6~8인 방은 `fillWithBots` 가 `IllegalStateException` 으로 실패한다
- `docs/stomp-protocol.md` 에 스컬킹 이벤트 10종·액션 2종 추가 (계약 문서 정본)
- 4인·6인 봇 풀매치 시뮬레이션 IT

### 이미 되어 있는 것 (다시 만들지 말 것)
룰 전부 + 라운드/매치 라이프사이클 + 합법 액션 + 타임아웃 액션 + 이벤트 sealed 계층 +
불변식 체커. 순수 시뮬레이션(`SkullKingMatchSimulationTest`)이 2~8인 완주를 이미 증명한다 —
S5 의 IT 는 **배선이 맞는지**를 보는 것이지 룰을 다시 검증하는 게 아니다.

### 완료 기준
**봇만으로 스컬킹 10라운드 완주**가 IT 로 통과. 티츄 회귀 전량 그린.

---

## S6 — 스컬킹 클라

**선행**: S5. **D-103**. **클라는 언제나 동시 1트랙**(parallel-tracks 직렬화 5).

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S6 를 진행해줘.
스컬킹 게임판 UI 를 만들어줘. 티츄 컴포넌트를 재사용하되 억지로 공유하진 말고.
```

### 범위
- `features/skullking/` — 게임판·입찰 UI·트릭 표시
- 좌석 배치가 **2~8 가변**이라 티츄의 4좌석 고정 기하를 그대로 못 쓴다 → 이게 최대 난관
- CSS 는 `styles/parts/` 에 신규 파일 + `index.css` 에 @import 추가
  (⚠ 폭 미디어는 `17-responsive.css` 에만 — D-94)

### 주의
- `tichuStore` 는 **미분리 상태**(T5 2차 이월). 스컬킹 스토어는 별도로 만들고
  티츄 스토어를 건드리지 않는다.
- i18n 은 하드코딩으로 시작해도 된다(D-94 결론: 대량 이관은 별건).

---

## 이월 / 별건

| 항목 | 상태 |
| --- | --- |
| 요트 | 포트 검증용 3번째 게임. 스컬킹 완료 후 재평가 |
| 할리갈리 | **실시간 판정 모델 결정 선행** — 별도 마일스톤 (`multi-game.md` §2) |
| T5 2차 (styles.css 조각 병합 · tichuStore 분리) | D-95 예약 |
| T2 (M2-C3 Sentry+Grafana) | D-91 예약 |
