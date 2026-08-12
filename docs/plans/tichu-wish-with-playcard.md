# 마작 소원을 `PLAY_CARD` 에 동봉 (D-108) — 설계

> 상태: **설계 승인** · 2026-08-05 · 결정 이력 `docs/decisions.md` D-108
> 계약 정본은 `docs/stomp-protocol.md`(액션 표)·`docs/rules-tichu.md`(§8.1, §9).
> 이 문서는 그 둘을 어떻게 고칠지와 코드 변경 범위를 적는다.

## 1. 문제

로컬 실플레이(2026-08-04)에서 마작(MAHJONG)을 리드로 내면 `MakeWishModal` 이 뜨자마자
사라졌다. 라운드 2회 연속 재현.

소원 창이 **"마작이 아직 트릭의 top 일 동안"** 으로만 열려 있다. 클라와 서버가 같은 조건을
쓴다:

| | 조건 |
| --- | --- |
| 클라 `useGameTableModel.ts` `myMahjongLeadActive` | `currentTopSeat === mySeat && currentTop.cards[0].special === 'MAHJONG' && activeWishRank === null` |
| 서버 `ActionValidator.validateMakeWish` | `top == [MAHJONG] && currentTopSeat == seat`, 아니면 `WISH_OUT_OF_CONTEXT` |

`applyPlayCard` 는 마작 플레이 직후 `TurnChanged(nextSeat)` 를 즉시 발행하므로 다음 좌석이
바로 낼 수 있다. 즉 **사람이 쓸 수 있는 시간 = 다음 플레이어가 카드를 낼 때까지**이고, 봇
방에서 그 값은 `mirboard.bot.delay-millis` 기본 **700ms**(`application.yml:154`)다. 사람끼리
해도 상대가 즉시 내면 같다.

**두 번째 결함**: 봇은 소원을 아예 못 낸다. `LegalActionEnumerator.playingCandidates` 에
`MakeWish` 후보가 없어(`bot/LegalActionEnumerator.java:73`), 봇이 마작을 리드하면 그 라운드
소원은 영구히 발생하지 않는다.

## 2. 진단 — 타이밍이 아니라 모델

원 티츄 룰에서 소원은 **마작을 내는 행위의 일부**(동시 선언)지, 낸 뒤에 따로 잡는 별도
창이 아니다. 현재 구현은 이를 사후 별도 액션(`MAKE_WISH`)으로 모델링해서 다음 플레이어와
경합이 생겼다. 딜레이를 늘려도 사람 상대에선 그대로 깨진다.

같은 뿌리의 갭이 이미 문서화되어 있다 — `docs/rules-tichu.md` §8.1:
"마작을 콤보(예: 1-2-3-4-5 STRAIGHT) 일부로 낸 후 wish 가능한지 명시 부재. 현재
`ActionValidator` 는 `currentTop.cards == [Mahjong]` 단독 요구."

## 3. 채택안과 기각안

**채택**: `PlayCard` 에 소원 랭크를 동봉하고 `MAKE_WISH` 액션을 제거한다.

**기각 ①: 서버 pending 상태.** 마작 플레이 후 서버가 소원 결정까지 턴 진행을 보류한다.
`TrickState` 에 pending 필드 + `pendingSeats` 반영 + `SKIP_WISH` 액션 + `WISH_PENDING`
이벤트 + `TimeoutActionPolicy` 정책 + 봇 처리 + 대기 UI + resync/탈주 상호작용이 전부
새로 필요하다. 잘못된 모델("별도 창")을 유지한 채 그 위에 상태머신을 얹는 방향이라 표면이
가장 크게 는다.

**기각 ②: 봇 딜레이 상향.** 사람 상대에선 그대로 깨지고 봇 소원 미구현도 남는다.

채택안이 이기는 지점: 경합이 **발생할 수 없는 모델**이 되고, 소원 결정이 제출 **전에**
끝나므로 기존 턴 타임아웃이 그대로 덮어 새 타임아웃 설계가 불필요하며, 클라 상태가
늘지 않고 **줄고**(모달 dismiss 상태머신 삭제), §2 의 콤보 갭이 함께 닫힌다.

## 4. 서버 설계

### 4.1 액션 계약 — `action/TichuAction.java`

```java
record PlayCard(List<Card> cards, Integer wishRank) implements TichuAction {
    public PlayCard { cards = List.copyOf(cards); }
    /** 소원 없이 내는 경우 — 기존 호출부 27곳 호환용. */
    public PlayCard(List<Card> cards) { this(cards, null); }
}
```

- `wishRank` 는 nullable `Integer`. null = 소원 없음.
- 편의 생성자 덕에 기존 `new TichuAction.PlayCard(List.of(c))` **27곳이 그대로 컴파일**된다.
- 와이어: `wishRank` 를 생략한 프레임은 null 로 역직렬화 → 구 클라 프레임 호환.
- `MakeWish` record 를 `permits` 절과 `@JsonSubTypes` 에서 삭제. `@action: "MAKE_WISH"` 는
  알 수 없는 판별자가 되어 `ERROR(INVALID_ACTION)` 으로 회신된다(기존 규약).

### 4.2 검증 — `action/ActionValidator.java`

`validateMakeWish` 와 그 switch case 삭제. `validatePlayCard` **끝**(기존 소원 *강제* 절 뒤)에
소원 *지정* 절을 추가한다:

```java
if (action.wishRank() != null) {
    if (action.cards().stream().noneMatch(c -> c.is(Special.MAHJONG))) {
        throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
    }
    if (action.wishRank() < 2 || action.wishRank() > 14) {
        throw reject(RejectionReason.INVALID_WISH_RANK);
    }
}
```

- 메서드 끝에 두는 이유: 턴·소유·족보 실패가 먼저 보고되어야 거절 사유가 읽힌다.
- `WISH_OUT_OF_CONTEXT` 는 의미가 "마작을 안 내면서 소원을 실었다"로 좁아져 **남는다**.
  `INVALID_WISH_RANK` 유지.
- **중복 소원 검사(`activeWish != null`)는 삭제**한다 — 마작은 덱에 1장뿐이라 두 번 지정이
  구조적으로 불가능하다.

### 4.3 엔진 — `TichuEngine.java`

`applyMakeWish` 와 그 switch case 삭제. `applyPlayCard` 의 기존 fulfillment 블록
**바로 뒤**에 삽입:

```java
if (action.wishRank() != null) {
    updatedWish = Wish.active(action.wishRank());
    events.add(new TichuEvent.WishMade(action.wishRank()));
}
```

- fulfillment **뒤**에 두는 이유: "마작을 낸 그 플레이가 자기 소원을 즉시 채우지 않는다"를
  코드 순서로 못박는다(마작 rank 1 vs 소원 2~14 라 실제로도 겹치지 않지만 순서에 의존하지
  않게).
- `updatedWish` 는 이미 `pendingTrick` 생성에 쓰이므로 라운드 종료·트릭 폐쇄 분기까지
  자동 전파된다.
- **`TichuEvent.WishMade`(`WISH_MADE`)는 유지** → 클라 리듀서·`activeWishRank`·헤더 표시가
  무변경. 한 프레임이 `PLAYED` + `WISH_MADE` 를 함께 발행하게 된다.

### 4.4 봇 — `bot/LegalActionEnumerator.java`

단일 카드 후보 루프에서 마작만 분기:

```java
for (Card c : me.hand()) {
    result.add(new TichuAction.PlayCard(List.of(c)));          // 소원 없음을 항상 먼저
    if (c.is(Special.MAHJONG)) {
        for (int r = 2; r <= 14; r++) {
            result.add(new TichuAction.PlayCard(List.of(c), r));
        }
    }
}
```

- 후보 +13. `RandomBotPolicy` 균등 선택 → 마작 리드 시 13/14 확률로 소원.
- **소원 없는 변형을 먼저 넣는 것이 요건**이다. `TimeoutActionPolicy` 는
  `weakest`(= `Card.rank()`) 동률에서 `Stream.min` 의 "먼저 온 것 유지" 성질로 고르므로,
  이 순서면 **타임아웃 동작이 현행과 완전히 동일**(소원 없이 마작)하게 남는다.
- 페어/트리플 후보는 `isNormal()` 필터라 마작이 섞이지 않는다 — 무변경.

## 5. 클라이언트 설계

### 5.1 플레이 플로우 — `features/tichu/useGameActions.ts`

`handlePlay` 를 "즉시 전송"에서 "마작이면 한 단계 경유"로 바꾼다.

| 선택 상태 | 동작 |
| --- | --- |
| 비어 있음 | 기존 에러(`play.error.pickCard`) |
| 마작 미포함 | 즉시 `PLAY_CARD {cards}` — 현행 그대로 |
| 마작 포함 | `setPendingWishPlay(selectedCards)` — 모달만 열고 아직 보내지 않음 |

모달 3분기:

| 조작 | 전송 |
| --- | --- |
| **소원 지정하고 내기** | `PLAY_CARD {cards, wishRank}` → `clearSelection()` + `pendingWishPlay=null` |
| **소원 없이 내기** | `PLAY_CARD {cards}` → `clearSelection()` + `pendingWishPlay=null` |
| **esc / 바깥 클릭** | **없음** — `pendingWishPlay=null` 만. 선택은 유지되어 다시 낼 수 있다 |

전송 성공 시에만 선택을 지운다는 점은 기존 `handlePlay` 와 같다(취소는 안 지운다).

esc 를 "소원 없이 내기"로 두면 모달을 무심코 닫았을 때 카드가 나가므로 취소로 잡는다.
기존 `MakeWishModal` 은 `onOpenChange` 의 dismiss 와 [건너뛰기] 를 같은 `onSkip` 으로 묶고
있으므로, props 를 `onConfirm` / `onSkipWish` / `onCancel` 3개로 나눈다.

### 5.2 상태 위치

`pendingWishPlay: Card[] | null` 은 `useGameTableModel.ts` 가 `useState` 로 소유한다 — 지금
`wishModalDismissed` 가 있는 자리와 같고, 파일 상단 주석("유일한 예외가 소원 모달 …")이
문구만 바꾸면 그대로 성립한다.

`useGameActions` 는 상태를 갖지 않는 핸들러 묶음이라는 성격을 유지한다. 모델이
`setPendingWishPlay` 를 args 로 주입하고(현재 `setWishModalDismissed` 를 주입하는 자리와
동일), 핸들러 3종(`handleConfirmWishPlay` / `handlePlayWithoutWish` / `handleCancelWishPlay`)이
그 setter 와 `sendAction` 만 쓴다. `GameTable.tsx` 는 `open={m.pendingWishPlay !== null}` 로
모달을 연결한다.

### 5.3 i18n — `i18n/messages.ts`

| 키 | 현재 | 변경 |
| --- | --- | --- |
| `wish.title` | 소원 — 다음에 강제할 랭크 | 마작을 냅니다 — 소원을 지정할까요? |
| `wish.body` | (건너뛰면 소원 없음) | 지정한 랭크를 다른 플레이어가 가능한 한 포함하도록 강제합니다. |
| `wish.skip` | 건너뛰기 (소원 없음) | 소원 없이 내기 |
| `wish.confirm` | 소원 지정 | 소원 지정하고 내기 |

취소 버튼 문구는 신설하지 않는다 — esc·바깥 클릭이 취소이고 별도 버튼을 두지 않는다.

## 6. 삭제 / 유지 목록

| 삭제 | 유지 (무변경) |
| --- | --- |
| `TichuAction.MakeWish` (record · permits · `@JsonSubTypes`) | `TichuEvent.WishMade` / `WISH_MADE` |
| `ActionValidator.validateMakeWish` + switch case | `Wish`, `TrickState.activeWish` |
| `TichuEngine.applyMakeWish` + switch case | `WishFulfillmentChecker` + 소원 **강제** 로직 전부 |
| 클라 `myMahjongLeadActive`·`wishContextKey`·`wishModalDismissed`·`showWishModal` | `TableView.activeWishRank`, `GameTableHeader` 소원 표시 |
| `useGameTableEffects.ts:67` 리셋 effect + 관련 args | `tichuStore` 리듀서 전부 |
| `handleMakeWish`·`handleSkipWish`, `types/tichu.ts` 의 `'MAKE_WISH'` | `RejectionReason.WISH_OUT_OF_CONTEXT`·`INVALID_WISH_RANK` (의미만 좁아짐) |

## 7. 에러 · 복구

- 검증 실패는 **상태 무변경**이므로 거절되면 카드도 나가지 않는다(기존 `ActionValidator`
  계약). 본인 큐 `ERROR` 로만 회신.
- 마작 포함 선택이 애초에 못 낼 조합이면 기존 `selectedPlayable` 이 내기 버튼을 비활성화
  하므로 모달까지 가지 않는다.
- 재접속/resync: 소원은 이미 `TableView.activeWishRank` 로 복원된다. `pendingWishPlay` 는
  클라 로컬이라 새로고침 시 사라지고 **카드는 나가지 않는다** — 안전한 방향의 유실.
- 턴 타임아웃: 별도 설계 불필요. 소원 결정이 플레이 제출 **전에** 일어나므로 기존 턴
  타임아웃이 그대로 덮는다.

## 8. 테스트

**서버**

| 대상 | 내용 |
| --- | --- |
| `ActionValidatorTest` | 기존 MakeWish 3케이스 재작성 — ① 마작 단독 + 유효 랭크 통과 ② 마작 미포함 + wishRank → `WISH_OUT_OF_CONTEXT` ③ rank 15 → `INVALID_WISH_RANK` ④ **신규 룰**: 1-2-3-4-5 스트레이트 + wishRank 통과 |
| `TichuSpecialCardScenarioTest:77` | 별도 `MakeWish` 호출을 동봉으로 교체. **원 버그의 회귀 테스트**로 "마작 플레이 한 번에 `WishMade` 발행 + `activeWish` 세팅 + 다음 좌석이 그 위에 낸 뒤에도 유지"를 검증 |
| `LegalActionEnumeratorTest` | 마작 보유 시 마작 후보가 14종(소원 없음 1 + 랭크 2~14 의 13), 그리고 **소원 없는 변형이 목록에서 먼저** |
| `RandomBotPolicyTest` · `BotMatchSimulationIT` | 봇 소원이 켜진 상태에서 매치가 교착 없이 정상 종료 |
| `GameStompControllerIntegrationTest` | 와이어에서 `PLAY_CARD {cards, wishRank}` 한 프레임이 `PLAYED` + `WISH_MADE` 를 내는지. 기존 `mahjong_leader_plays_...` 테스트가 `forcePlayingFromDealing` 으로 마작 리더를 결정론적으로 만들고 있어, 셋업 복제 없이 그 테스트를 확장했다 |

**클라**

| 대상 | 내용 |
| --- | --- |
| `GameTable.test.tsx` | 마작 포함 선택 후 내기 → 모달 노출 / [소원 지정하고 내기] → `wishRank` 포함 전송 / [소원 없이 내기] → `wishRank` 없이 전송 / esc → **전송 0건** |
| `tichuStore.applyEvent.test.ts` | 무변경 통과 확인(`WISH_MADE`, `TRICK_TAKEN` 유지 케이스) |

**수동**: `docs/qa-scenarios.md` 의 마작 시나리오를 새 절차로 교체하고 봇 방에서 실행.

## 9. 문서 변경 (코드보다 먼저)

| 순서 | 파일 | 내용 |
| --- | --- | --- |
| 1 | `docs/decisions.md` | **D-108 추가 (완료)** |
| 2 | `docs/plans/tichu-wish-with-playcard.md` | 본 문서 |
| 3 | `docs/stomp-protocol.md` | `PLAY_CARD` 행에 `wishRank?: 2..14` 추가, `MAKE_WISH` 행 삭제 |
| 4 | `docs/rules-tichu.md` | §8.1 소원 시점 = 마작 플레이와 **동시**, "콤보 갭" 해소 표기. §9 의 "follow 강제 deferred — 10C 에서 마감 예정" 은 이미 구현됐는데 문서가 낡음 → 함께 정정 |
| 5 | `CLAUDE.md:219` | 티츄 액션 목록에서 `MAKE_WISH` 제거 |
| 6 | `docs/implementation-status.md:130,138` | 액션 목록 · 모달 연동 서술 갱신 |
| 7 | `docs/qa-scenarios.md:194-197` | 재현/검증 절차 교체 |
| 8 | `docs/plans/mvp-roadmap.md` | 트랙 A 후속(라이브 실플레이 결함)으로 한 줄 기록 |

**`docs/game-port.md` 는 무변경** — `TichuAction` 은 `domain.game.tichu` 내부이고 인프라
변경은 0건이다. 이 사실 자체가 D-98 포트 추출의 회귀 검증이기도 하다.

## 10. 범위 밖

- 봇의 소원 **휴리스틱**(어떤 랭크가 유리한가). 이번엔 기존 균등 분포 정책을 그대로 쓴다.
- 소원 강제 로직(`WishFulfillmentChecker`) 확장 — 현재 단일/페어/트리플 + Phoenix 조합만
  보고 콤보는 미포함. 별건으로 남는다.
- 스컬킹 등 다른 게임 — 포트 무변경이라 영향 없음.
