# 마작 소원 `PLAY_CARD` 동봉 (D-108) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 티츄의 마작 소원을 별도 `MAKE_WISH` 액션에서 `PLAY_CARD` 의 옵션 필드로 옮겨,
다음 플레이어와의 경합으로 실사용이 불가능하던 결함을 모델 수준에서 제거한다.

**Architecture:** `TichuAction.PlayCard` 에 nullable `wishRank` 를 추가하고 `MakeWish` 액션을
삭제한다. 검증은 "낸 카드에 마작이 포함되면 소원 허용"으로 일반화되어 콤보 속 마작도
소원을 걸 수 있게 된다. 클라는 마작이 포함된 선택을 낼 때 소원 모달을 먼저 거쳐 한
프레임으로 전송한다. `WISH_MADE` 이벤트·소원 강제 로직·`TableView.activeWishRank` 는 전부
그대로라 이벤트 수신 경로는 손대지 않는다.

**Tech Stack:** Java 25 / Spring Boot 4.0.1 (sealed interface + record), JUnit 5 + AssertJ /
Vite + React 18 + TypeScript, Zustand, Vitest + React Testing Library.

**설계 정본:** `docs/plans/tichu-wish-with-playcard.md` · **결정 이력:** `docs/decisions.md` D-108

## Global Constraints

- 작업 디렉토리는 리포 루트 절대경로 `/Users/yupchang/Developer/mirboard/.claude/worktrees/priceless-nightingale-cd6cfe`.
  `npm --prefix client ...` / `./gradlew ...` 는 전부 여기서 실행한다.
- **Server-Authoritative**: 클라가 보낸 `wishRank` 는 검증 대상이지 신뢰 대상이 아니다.
  룰 판정은 전부 `ActionValidator` / `TichuEngine` 에서만 한다. 클라에 룰 로직 금지.
- **도메인 경계**: 이 작업의 **프로덕션 코드** 변경은 `server/src/main/java/com/mirboard/domain/game/tichu/**`
  와 클라 `client/src/features/tichu/**`(+ `i18n/messages.ts`, `types/tichu.ts`) 안에서만
  끝난다. `server/src/main/java/com/mirboard/infra/**` 와
  `server/src/main/java/com/mirboard/domain/game/core/**` 는 **한 줄도 바뀌지 않는다** —
  바뀌면 설계가 틀린 것이다.
  **테스트는 예외**: Task 6 이 `server/src/test/java/com/mirboard/infra/ws/GameStompControllerIntegrationTest.java`
  를 고친다(와이어 계약 검증). 테스트 수정은 이 제약의 대상이 아니다.
- **`javax.*` 금지, `jakarta.*` 만 사용** (이 작업에는 해당 import 없음).
- 커밋 시 pre-commit 훅 `check:fast`(클라 tsc + vitest + 서버 compile)가 자동 게이트다.
  훅이 실패하면 커밋이 막히므로 각 Task 의 테스트 단계를 건너뛰지 말 것.
- 문서 수정은 코드보다 **먼저** (Task 1). `docs/decisions.md` D-108 은 이미 작성되어 있다.
- 한국어 프로젝트 — 주석·문서·커밋 메시지는 한국어로 작성한다.

---

## File Structure

| 파일 | 책임 | Task |
| --- | --- | --- |
| `docs/stomp-protocol.md` | 와이어 계약 정본 — 티츄 액션 표 | 1 |
| `docs/rules-tichu.md` | 룰 정본 — §8.1 마작, §9 소원 강제 | 1 |
| `CLAUDE.md` | 액션 카탈로그 요약 | 1 |
| `docs/implementation-status.md` | 기능 현황 | 1 |
| `docs/qa-scenarios.md` | 수동 검증 절차 | 1 |
| `docs/plans/mvp-roadmap.md` | 트랙 기록 | 1 |
| `server/.../action/TichuAction.java` | 액션 sealed 계층 | 2, 3 |
| `server/.../action/ActionValidator.java` | 합법성 검증 (상태 불변) | 2, 3 |
| `server/.../TichuEngine.java` | 액션 → 상태 전이 + 이벤트 | 2, 3 |
| `server/.../bot/LegalActionEnumerator.java` | 봇/타임아웃 후보 생성 | 4 |
| `client/.../tichu/useGameTableModel.ts` | 스토어 구독 + 파생값 (+ 모달 대기 상태) | 5 |
| `client/.../tichu/useGameActions.ts` | 입력 → 서버 액션 번역 (무상태) | 5 |
| `client/.../tichu/useGameTableEffects.ts` | 부수효과 | 5 |
| `client/.../tichu/MakeWishModal.tsx` | 소원 모달 (프레젠테이션) | 5 |
| `client/.../tichu/GameTable.tsx` | 조립 루트 | 5 |
| `client/src/i18n/messages.ts` | 문구 | 5 |
| `client/src/types/tichu.ts` | 액션 타입 유니온 | 5 |

---

## Task 1: 문서 계약 갱신 (코드 변경 0)

레포 규약상 설계 변경은 문서가 먼저다. 이 Task 는 코드를 건드리지 않는다.

**Files:**
- Modify: `docs/stomp-protocol.md:141`
- Modify: `docs/rules-tichu.md` (§8.1, §9)
- Modify: `CLAUDE.md:219`
- Modify: `docs/implementation-status.md:129-131, 138-139`
- Modify: `docs/qa-scenarios.md:188-198`
- Modify: `docs/plans/mvp-roadmap.md` (트랙 외 기록 블록 뒤)

**Interfaces:**
- Consumes: `docs/decisions.md` D-108 (이미 작성됨), `docs/plans/tichu-wish-with-playcard.md`
- Produces: 없음 (문서 전용). 이후 Task 들이 이 문서와 일치하는지로 검증된다.

- [ ] **Step 1: `docs/stomp-protocol.md` 액션 표 수정**

찾을 두 줄:

```markdown
| `PLAY_CARD` | `cards: Card[]` | 일반 플레이 (Phoenix 해석은 서버가 결정) |
| `PASS_TRICK` | — | 트릭 패스 (리드 차례에는 불가) |
| `MAKE_WISH` | `rank: 2..14` | Mahjong 을 낸 직후만 |
```

바꿀 내용 (`MAKE_WISH` 행 삭제, `PLAY_CARD` 행에 옵션 필드 추가):

```markdown
| `PLAY_CARD` | `cards: Card[]`, `wishRank?: 2..14` | 일반 플레이 (Phoenix 해석은 서버가 결정). `wishRank` 는 낸 카드에 Mahjong 이 포함될 때만 허용 — 소원은 마작을 내는 액션에 동봉한다(D-108) |
| `PASS_TRICK` | — | 트릭 패스 (리드 차례에는 불가) |
```

- [ ] **Step 2: `docs/rules-tichu.md` §8.1 Mahjong 절 수정**

찾을 내용:

```markdown
- **Wish 활성**: Mahjong 을 단독 또는 콤보로 낸 **직후** 한 번에 한해 rank 2..14 중 하나를 wish 로 지정 가능 (생략 가능).
```

바꿀 내용:

```markdown
- **Wish 활성**: Mahjong 을 내는 **그 액션에 함께** rank 2..14 중 하나를 wish 로 지정 (생략 가능). 단독 리드든 콤보(예: 1-2-3-4-5 STRAIGHT)의 일부든 무관 — 낸 카드에 Mahjong 이 포함되면 지정 가능. **별도 `MAKE_WISH` 액션은 없다** (D-108 폐기 — 사후 별도 창은 다음 플레이어가 카드를 내는 순간 닫혀 실사용이 불가능했다).
```

- [ ] **Step 3: `docs/rules-tichu.md` §8.1 코드/테스트/갭 블록 수정**

찾을 내용:

```markdown
**코드:**
- 리드 결정: `TichuEngine.mahjongHolder` (`TichuEngine.java:195-202`)
- Wish 액션: `TichuAction.MakeWish(rank)`, `applyMakeWish` (`TichuEngine.java:328-338`)
- Wish 검증: `ActionValidator.validateMakeWish` (`ActionValidator.java:178-196`) — Mahjong 직후 + currentTopSeat==me + 중복 금지

**테스트:** `ActionValidatorTest` (Wish 활성/비활성 케이스).

**갭:** Mahjong 자체를 콤보 (예: 1-2-3-4-5 STRAIGHT) 일부로 낸 후 wish 가능한지 명시 부재. 현재 ActionValidator 는 `currentTop.cards == [Mahjong]` 단독 요구.
```

바꿀 내용:

```markdown
**코드:**
- 리드 결정: `TichuEngine.mahjongHolder`
- Wish 지정: `TichuAction.PlayCard(cards, wishRank)` 의 `wishRank` — `TichuEngine.applyPlayCard` 가 `Wish.active(...)` 세팅 + `WishMade` 이벤트 발행
- Wish 검증: `ActionValidator.validatePlayCard` 의 소원 지정 절 — `wishRank != null` 인데 낸 카드에 Mahjong 이 없으면 `WISH_OUT_OF_CONTEXT`, rank 가 2..14 밖이면 `INVALID_WISH_RANK`

**테스트:** `ActionValidatorTest` (마작 단독 / 스트레이트 동봉 / 마작 미포함 / 랭크 범위), `TichuSpecialCardScenarioTest` (동봉 1회로 활성 + 다음 좌석이 그 위에 낸 뒤에도 유지).

**갭:** 없음 — D-108 에서 콤보 동봉 갭이 닫혔다. "소원 1회" 제한은 Mahjong 이 덱에 1장뿐이라 구조적으로 보장되므로 별도 중복 검사가 없다.
```

- [ ] **Step 4: `docs/rules-tichu.md` §9 의 낡은 "deferred" 서술 정정**

`ActionValidator` 는 이미 follow 강제를 구현했는데(코드 주석 "Phase 10C — D-58 마감")
문서만 낡아 있다. 이 작업에서 §9 를 손대므로 함께 고친다.

찾을 내용:

```markdown
**현재 구현 (`ActionValidator.java:76-86`)**:
- **lead**: 보유한 wish rank + 미포함 플레이 → reject (`WISH_NOT_FULFILLED`)
- **follow**: **deferred** — line 83 주석 "Strict only on lead; on follow, deferred (need beat check)". 현재 미구현. **10C 에서 마감 예정**.
```

바꿀 내용:

```markdown
**현재 구현 (`ActionValidator.validatePlayCard` 의 wish 강제 절)**:
- **lead**: 보유한 wish rank + 미포함 플레이 → reject (`WISH_NOT_FULFILLED`)
- **follow**: wish rank 를 포함한 **합법 follow 가 존재하면** 미포함 플레이 → reject. 존재 판정은 `WishFulfillmentChecker.canPlayWishRank`. **10C(D-58) 에서 마감 완료**.
```

이어서 §9 끝의 갭 줄을 찾는다:

```markdown
**갭:** follow 강제 미구현. wish + BOMB 인터럽트 시 fulfillment 처리 명시 부재. **10C 에서 마감**.
```

바꿀 내용:

```markdown
**갭:** `WishFulfillmentChecker` 가 콤보(스트레이트/풀하우스/연속페어)를 보지 않아, 콤보로만 wish rank 를 낼 수 있는 상황에서는 강제되지 않는다. wish + BOMB 인터럽트 시 fulfillment 처리도 명시 부재.
```

- [ ] **Step 5: `CLAUDE.md` 액션 목록에서 `MAKE_WISH` 제거**

찾을 내용 (219행):

```markdown
  - 티츄: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `READY`, `PASS_CARDS`, `PLAY_CARD`, `PASS_TRICK`, `MAKE_WISH`, `GIVE_DRAGON_TRICK`
```

바꿀 내용:

```markdown
  - 티츄: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `READY`, `PASS_CARDS`, `PLAY_CARD`(마작 포함 시 `wishRank` 동봉, D-108), `PASS_TRICK`, `GIVE_DRAGON_TRICK`
```

- [ ] **Step 6: `docs/implementation-status.md` 두 곳 수정**

찾을 내용 (§5.2):

```markdown
`DeclareGrandTichu`, `DeclareTichu`, `Ready`, `PassCards`, `PlayCard`(phoenixAs override),
`PassTrick`, `MakeWish`, `GiveDragonTrick`. 페이즈별 검증은 `ActionValidator`,
소원 충족 판정은 `WishFulfillmentChecker`.
```

바꿀 내용 (`MakeWish` 제거 + `PlayCard` 설명을 실제 record 필드로 정정):

```markdown
`DeclareGrandTichu`, `DeclareTichu`, `Ready`, `PassCards`, `PlayCard`(cards + 선택 `wishRank`),
`PassTrick`, `GiveDragonTrick`. 페이즈별 검증은 `ActionValidator`,
소원 충족 판정은 `WishFulfillmentChecker`.
```

찾을 내용 (§5.3 끝):

```markdown
- 마작 플레이 → MAKE_WISH 흐름, 드래곤 트릭 → GIVE_DRAGON_TRICK 흐름이 클라 모달과 연동
  (`MakeWishModal`, `GiveDragonTrickModal`).
```

바꿀 내용:

```markdown
- 마작 소원은 `PLAY_CARD` 에 `wishRank` 를 동봉해 한 프레임으로 처리(D-108) — 클라는
  마작이 포함된 선택을 낼 때 `MakeWishModal` 을 먼저 거친다. 드래곤 트릭 → GIVE_DRAGON_TRICK
  흐름은 기존대로 `GiveDragonTrickModal` 과 연동.
```

- [ ] **Step 7: `docs/qa-scenarios.md` 시나리오 1 교체**

찾을 내용 (`### 시나리오 1` 부터 `### 시나리오 2` 직전까지):

```markdown
### 시나리오 1 — Mahjong 소원 (6E-1)

1. 4탭으로 게임 시작 (정원 4 + 전원 준비 → IN_GAME).
2. Dealing(8) → Dealing(14) → Passing → Playing 진입까지 Ready/카드 패스 진행.
3. 첫 리드 차례 (헤더 `현재 차례`) 가 Mahjong 보유자 (손패에 rank=1 카드) 인 탭에서
   Mahjong 단독 클릭 → "내기".
4. **기대**: `MakeWishModal` 자동 노출. rank 2~14 그리드 + 건너뛰기 / 소원 지정.
5. rank 선택 후 "소원 지정" → 헤더의 `활성 소원: N` 표시.
6. 다른 클라 탭 헤더에서도 동일 `활성 소원: N` 확인 → STOMP fan-out 정상.
7. **실패 시**: 서버 로그 `Action rejected: MAKE_WISH reason=WISH_OUT_OF_CONTEXT` 검색.
   currentTopSeat 이 본인이고 currentTop 이 Mahjong 단독이어야 함.
```

바꿀 내용:

```markdown
### 시나리오 1 — Mahjong 소원 (D-108)

1. 게임 시작 (정원 + 전원 준비 → IN_GAME). **봇 채우기 방으로도 검증 가능** — 소원 창이
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
   (봇은 소원 없음 1 + 랭크 13 후보를 균등 선택하므로 대체로 소원을 건다).
9. **실패 시**: 서버 로그 `Action rejected: PLAY_CARD reason=WISH_OUT_OF_CONTEXT` 검색.
   `wishRank` 를 실으려면 낸 카드에 Mahjong 이 포함되어야 한다.
```

- [ ] **Step 8: `docs/plans/mvp-roadmap.md` 에 트랙 외 기록 추가**

찾을 내용 (트랙 외 기술부채 블록의 마지막 줄):

```markdown
직후 라이브 401px 실측에서 나온 게임판 결함 4건은 D-88 에서 보정.
```

바꿀 내용 (같은 줄 + 새 블록을 이어 붙인다):

```markdown
직후 라이브 401px 실측에서 나온 게임판 결함 4건은 D-88 에서 보정.

**트랙 외 룰 결함(D-108)**: 로컬 실플레이(2026-08-04)에서 마작 소원(MAKE_WISH)이 실사용
불가로 드러났다. 소원 창이 "마작이 아직 트릭 top 인 동안"으로만 열려 있어 다음 플레이어가
카드를 내는 순간 닫혔고(봇 방 기준 700ms), 봇은 소원 후보를 아예 만들지 않아 봇이 마작을
리드하면 소원이 영구히 발생하지 않았다. 원 티츄 룰대로 **소원을 `PLAY_CARD` 에 동봉**하고
별도 `MAKE_WISH` 액션을 폐기해 경합을 모델에서 제거했다. 콤보 속 마작도 소원 가능해져
`rules-tichu.md` §8.1 갭이 함께 닫혔고, `GameEngine` 포트·infra 는 무변경이라 D-98 포트
추출의 회귀 검증도 된다. 설계는 `docs/plans/tichu-wish-with-playcard.md`,
실행 계획은 `docs/plans/tichu-wish-with-playcard-tasks.md`.
```

- [ ] **Step 9: 문서 일관성 확인**

Run:

```bash
grep -rn "MAKE_WISH\|MakeWish" docs/ CLAUDE.md README.md
```

Expected: `docs/decisions.md` 안의 **이력 서술**(D-34, D-108 등 과거 결정 설명)만 남고,
`docs/stomp-protocol.md` · `docs/rules-tichu.md` · `CLAUDE.md` · `docs/implementation-status.md` ·
`docs/qa-scenarios.md` 에는 `MAKE_WISH` 액션을 **현재 계약으로 소개하는** 문장이 없어야 한다.
(`docs/plans/mvp-roadmap.md` 의 Phase 1 액션 목록·6E-1 완료 기록과 `docs/decisions.md` 는
과거 기록이므로 건드리지 않는다.)

- [ ] **Step 10: 커밋**

```bash
git add docs/stomp-protocol.md docs/rules-tichu.md CLAUDE.md docs/implementation-status.md docs/qa-scenarios.md docs/plans/mvp-roadmap.md docs/decisions.md docs/plans/tichu-wish-with-playcard.md docs/plans/tichu-wish-with-playcard-tasks.md
git commit -m "docs: 마작 소원을 PLAY_CARD 에 동봉 — 계약 문서 선행 갱신 (D-108)"
```

---

## Task 2: 서버 — `PlayCard.wishRank` 동봉 경로 추가

`MAKE_WISH` 는 아직 남겨 둔다. 이 Task 만으로도 두 경로가 모두 동작해야 한다
(리뷰어가 Task 3 을 거부하고 이 Task 만 승인할 수 있게).

**Files:**
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/action/TichuAction.java:55-59`
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/action/ActionValidator.java:42-95`
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/TichuEngine.java` (`applyPlayCard` 내 wish 블록)
- Test: `server/src/test/java/com/mirboard/domain/game/tichu/action/ActionValidatorTest.java`
- Test: `server/src/test/java/com/mirboard/domain/game/tichu/TichuSpecialCardScenarioTest.java`

**Interfaces:**
- Consumes: 기존 `RejectionReason.WISH_OUT_OF_CONTEXT`, `RejectionReason.INVALID_WISH_RANK`,
  `com.mirboard.domain.game.tichu.card.Wish.active(int)`,
  `com.mirboard.domain.game.tichu.event.TichuEvent.WishMade(int rank)`
- Produces:
  - `TichuAction.PlayCard(List<Card> cards, Integer wishRank)` — 정규 생성자
  - `TichuAction.PlayCard(List<Card> cards)` — 편의 생성자, `wishRank = null`
  - `PlayCard#wishRank()` → `Integer` (nullable). Task 4 의 봇 후보 생성이 이 두 생성자를 쓴다.

- [ ] **Step 1: 실패하는 검증 테스트를 쓴다**

`ActionValidatorTest.java` 의 `// ---------- MakeWish ----------` 섹션 **바로 위**에
다음 섹션을 추가한다. (기존 MakeWish 테스트 3개는 Task 3 에서 지운다 — 지금은 둘 다 산다.)

```java
    // ---------- PlayCard 에 동봉한 소원 (D-108) ----------

    @Test
    void wish_bundled_with_solo_mahjong_lead_is_accepted() {
        var state = playingState(
                List.of(List.of(Card.mahjong()),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatCode(() -> ActionValidator.validate(state, 0,
                new TichuAction.PlayCard(List.of(Card.mahjong()), 7)))
                .doesNotThrowAnyException();
    }

    @Test
    void wish_bundled_with_straight_containing_mahjong_is_accepted() {
        // D-108 신규 룰: 마작을 콤보(1-2-3-4-5)의 일부로 내도 소원을 걸 수 있다.
        var straight = List.of(
                Card.mahjong(), n(Suit.JADE, 2), n(Suit.SWORD, 3),
                n(Suit.STAR, 4), n(Suit.PAGODA, 5));
        var state = playingState(
                List.of(straight,
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatCode(() -> ActionValidator.validate(state, 0,
                new TichuAction.PlayCard(straight, 9)))
                .doesNotThrowAnyException();
    }

    @Test
    void wish_bundled_without_mahjong_is_rejected() {
        var state = playingState(
                List.of(List.of(n(Suit.JADE, 9)),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatThrownBy(() -> ActionValidator.validate(state, 0,
                new TichuAction.PlayCard(List.of(n(Suit.JADE, 9)), 7)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.WISH_OUT_OF_CONTEXT);
    }

    @Test
    void bundled_wish_rank_out_of_range_is_rejected() {
        var state = playingState(
                List.of(List.of(Card.mahjong()),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatThrownBy(() -> ActionValidator.validate(state, 0,
                new TichuAction.PlayCard(List.of(Card.mahjong()), 15)))
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.INVALID_WISH_RANK);
    }

    @Test
    void play_without_wish_rank_is_unaffected() {
        var state = playingState(
                List.of(List.of(Card.mahjong()),
                        List.of(n(Suit.SWORD, 6)),
                        List.of(n(Suit.STAR, 7)),
                        List.of(n(Suit.PAGODA, 8))),
                leadTrick(0));

        assertThatCode(() -> ActionValidator.validate(state, 0,
                new TichuAction.PlayCard(List.of(Card.mahjong()))))
                .doesNotThrowAnyException();
    }
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.action.ActionValidatorTest"
```

Expected: FAIL — 컴파일 에러 `constructor PlayCard in record PlayCard cannot be applied to given types`
(2-인자 생성자가 아직 없다).

- [ ] **Step 3: `PlayCard` 에 `wishRank` 를 추가한다**

`TichuAction.java` 에서 찾을 내용:

```java
    /** 차례에서 손패 묶음을 트릭에 낸다. 폭탄은 다른 차례에도 인터럽트 가능. */
    record PlayCard(List<Card> cards) implements TichuAction {
        public PlayCard {
            cards = List.copyOf(cards);
        }
    }
```

바꿀 내용:

```java
    /**
     * 차례에서 손패 묶음을 트릭에 낸다. 폭탄은 다른 차례에도 인터럽트 가능.
     *
     * <p>{@code wishRank} 는 Mahjong 소원(2~14). null 이면 소원 없음. 소원은 마작을
     * 내는 행위의 일부라 별도 액션이 아니라 여기에 동봉한다 (D-108) — 사후 별도
     * 액션이던 시절엔 다음 플레이어가 카드를 내는 순간 창이 닫혀 실사용이 불가능했다.
     */
    record PlayCard(List<Card> cards, Integer wishRank) implements TichuAction {
        public PlayCard {
            cards = List.copyOf(cards);
        }

        /** 소원 없이 내는 경우. */
        public PlayCard(List<Card> cards) {
            this(cards, null);
        }
    }
```

- [ ] **Step 4: `ActionValidator` 에 소원 지정 절을 추가한다**

`validatePlayCard` 의 마지막 블록을 찾는다:

```java
                if (WishFulfillmentChecker.canPlayWishRank(
                        player.hand(), trick.currentTop(), wishedRank)) {
                    throw reject(RejectionReason.WISH_NOT_FULFILLED);
                }
            }
        }
    }
```

바꿀 내용:

```java
                if (WishFulfillmentChecker.canPlayWishRank(
                        player.hand(), trick.currentTop(), wishedRank)) {
                    throw reject(RejectionReason.WISH_NOT_FULFILLED);
                }
            }
        }

        // 소원 "지정" (D-108) — 위의 소원 "강제" 와 다른 관심사다. 마작을 내는 그
        // 액션에 동봉하며, 단독 리드든 콤보의 일부든 낸 카드에 마작이 포함되면 허용.
        // 메서드 끝에 두는 이유: 턴·소유·족보 실패가 먼저 보고되어야 거절 사유가 읽힌다.
        // "소원 1회" 는 마작이 덱에 1장뿐이라 구조적으로 보장되므로 중복 검사가 없다.
        if (action.wishRank() != null) {
            if (action.cards().stream().noneMatch(c -> c.is(Special.MAHJONG))) {
                throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
            }
            if (action.wishRank() < 2 || action.wishRank() > 14) {
                throw reject(RejectionReason.INVALID_WISH_RANK);
            }
        }
    }
```

`Special` 은 이미 `import com.mirboard.domain.game.tichu.card.Special;` 로 들어와 있다
(Dog 분기가 쓴다). 추가 import 불필요.

- [ ] **Step 5: 검증 테스트가 통과하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.action.ActionValidatorTest"
```

Expected: PASS (기존 MakeWish 테스트 3개 포함 전부 그린).

- [ ] **Step 6: 실패하는 엔진 테스트를 쓴다**

`TichuSpecialCardScenarioTest.java` 의 `mahjong_lead_then_make_wish_activates` **바로 아래**에
추가한다:

```java
    @Test
    void mahjong_lead_with_bundled_wish_activates_in_one_action() {
        // D-108 회귀 테스트 — 원 버그는 "다음 사람이 마작 위에 내면 소원 창이 닫힌다"
        // 였다. 소원을 플레이에 동봉하면 창 자체가 없으므로, 다음 좌석이 낸 뒤에도
        // 소원이 살아 있는지로 고정한다.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.mahjong(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        var afterMahjong = engine.apply(state, 0,
                new TichuAction.PlayCard(List.of(Card.mahjong()), 7));

        // 한 액션이 PLAYED 와 WISH_MADE 를 함께 낸다.
        assertThat(afterMahjong.events())
                .anyMatch(e -> e instanceof com.mirboard.domain.game.tichu.event.TichuEvent.Played)
                .anyMatch(e -> e instanceof com.mirboard.domain.game.tichu.event.TichuEvent.WishMade w
                        && w.rank() == 7);

        var trick = ((TichuState.Playing) afterMahjong.newState()).trick();
        assertThat(trick.activeWish()).isNotNull();
        assertThat(trick.activeWish().rank()).isEqualTo(7);
        assertThat(trick.activeWish().fulfilled()).isFalse();

        // 다음 좌석(1)이 마작 위에 카드를 낸다 — 예전엔 여기서 소원 창이 닫혔다.
        TichuState afterFollow = play(engine, afterMahjong.newState(), 1, n(Suit.SWORD, 7));
        var followTrick = ((TichuState.Playing) afterFollow).trick();
        assertThat(followTrick.activeWish()).isNotNull();
        assertThat(followTrick.activeWish().rank()).isEqualTo(7);
    }
```

- [ ] **Step 7: 엔진 테스트가 실패하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.TichuSpecialCardScenarioTest"
```

Expected: FAIL — `mahjong_lead_with_bundled_wish_activates_in_one_action` 에서
`Expecting any element ... to match` (아직 `WishMade` 를 발행하지 않으므로) 또는
`expected: not null but was: null` (`activeWish` 미설정).

- [ ] **Step 8: `TichuEngine.applyPlayCard` 에 소원 지정을 넣는다**

찾을 내용:

```java
        Wish wishBefore = trick.activeWish();
        Wish updatedWish = wishBefore;
        if (wishBefore != null && wishBefore.isActive()
                && action.cards().stream().anyMatch(c -> c.isNormal() && c.rank() == wishBefore.rank())) {
            updatedWish = wishBefore.fulfill();
        }
```

바꿀 내용 (블록 **뒤**에 이어 붙인다):

```java
        Wish wishBefore = trick.activeWish();
        Wish updatedWish = wishBefore;
        if (wishBefore != null && wishBefore.isActive()
                && action.cards().stream().anyMatch(c -> c.isNormal() && c.rank() == wishBefore.rank())) {
            updatedWish = wishBefore.fulfill();
        }

        // 소원 지정 (D-108). fulfillment **뒤**에 둔다 — "마작을 낸 그 플레이가 자기
        // 소원을 즉시 채우지 않는다"를 코드 순서로 못박는다(마작 rank 1, 소원 2~14 라
        // 실제로도 겹치지 않지만 순서에 의존하지 않게). 검증은 ActionValidator 가 마쳤다.
        if (action.wishRank() != null) {
            updatedWish = Wish.active(action.wishRank());
            events.add(new TichuEvent.WishMade(action.wishRank()));
        }
```

`Wish` 와 `TichuEvent` 는 이미 import 되어 있다 (각각 `wishBefore` 선언과 `applyMakeWish` 가 쓴다).

- [ ] **Step 9: 엔진 테스트가 통과하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.TichuSpecialCardScenarioTest"
```

Expected: PASS (기존 `mahjong_lead_then_make_wish_activates` 포함 전부 그린).

- [ ] **Step 10: 티츄 도메인 전체 회귀를 돌린다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.*"
```

Expected: PASS. 특히 `TichuEngineRoundSimulationTest` · `DealingLifecycleTest` ·
`TichuGameEngine*Test` 가 그린이어야 한다 (편의 생성자로 기존 호출부가 무변경임을 확인).

- [ ] **Step 11: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/tichu/action/TichuAction.java server/src/main/java/com/mirboard/domain/game/tichu/action/ActionValidator.java server/src/main/java/com/mirboard/domain/game/tichu/TichuEngine.java server/src/test/java/com/mirboard/domain/game/tichu/action/ActionValidatorTest.java server/src/test/java/com/mirboard/domain/game/tichu/TichuSpecialCardScenarioTest.java
git commit -m "feat(tichu): PLAY_CARD 에 wishRank 동봉 — 마작 소원을 플레이와 원자화 (D-108)"
```

---

## Task 3: 서버 — `MAKE_WISH` 액션 제거

**Files:**
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/action/TichuAction.java` (permits · `@JsonSubTypes` · record)
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/action/ActionValidator.java` (switch case · `validateMakeWish`)
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/TichuEngine.java` (switch case · `applyMakeWish`)
- Test: `server/src/test/java/com/mirboard/domain/game/tichu/action/ActionValidatorTest.java` (MakeWish 섹션 삭제)
- Test: `server/src/test/java/com/mirboard/domain/game/tichu/TichuSpecialCardScenarioTest.java` (`mahjong_lead_then_make_wish_activates` 삭제)

**Interfaces:**
- Consumes: Task 2 의 `PlayCard(cards, wishRank)`
- Produces: `TichuAction` sealed 계층에서 `MakeWish` 가 사라진다. 이후 어떤 코드도
  `TichuAction.MakeWish` 를 참조하면 안 된다.

- [ ] **Step 1: 낡은 테스트를 먼저 지운다**

`ActionValidatorTest.java` 에서 `// ---------- MakeWish ----------` 주석과 그 아래 3개 테스트
(`wish_must_follow_mahjong_play`, `wish_after_non_mahjong_play_is_rejected`,
`invalid_wish_rank_is_rejected`)를 통째로 삭제한다. 세 케이스의 의도는 Task 2 에서 추가한
`wish_bundled_*` / `bundled_wish_rank_out_of_range_is_rejected` 가 이미 덮는다.

`TichuSpecialCardScenarioTest.java` 에서 `mahjong_lead_then_make_wish_activates` 테스트를
통째로 삭제한다. Task 2 의 `mahjong_lead_with_bundled_wish_activates_in_one_action` 이 대체한다.

- [ ] **Step 2: `TichuAction` 에서 `MakeWish` 를 뺀다**

세 곳을 고친다.

`@JsonSubTypes` 에서 찾을 줄:

```java
        @JsonSubTypes.Type(value = TichuAction.MakeWish.class, name = "MAKE_WISH"),
```

→ **줄 삭제**. (알 수 없는 `@action` 판별자는 `ERROR(INVALID_ACTION)` 으로 회신된다.)

`permits` 절에서 찾을 줄:

```java
                TichuAction.MakeWish,
```

→ **줄 삭제**.

record 선언에서 찾을 내용:

```java
    /** Mahjong 을 낸 직후, 한 번에 한해 2~14 중 한 rank 를 소원으로 지정. */
    record MakeWish(int rank) implements TichuAction {
    }

```

→ **블록 삭제** (뒤따르는 빈 줄 포함).

- [ ] **Step 3: `ActionValidator` 에서 `MakeWish` 를 뺀다**

switch 에서 찾을 줄:

```java
            case TichuAction.MakeWish w -> validateMakeWish(state, seat, w);
```

→ **줄 삭제**.

`validateMakeWish` 메서드 전체를 찾는다:

```java
    // ---------- MakeWish ----------
    private static void validateMakeWish(TichuState state, int seat, TichuAction.MakeWish action) {
        TichuState.Playing playing = requirePlaying(state);
        if (action.rank() < 2 || action.rank() > 14) {
            throw reject(RejectionReason.INVALID_WISH_RANK);
        }
        TrickState trick = playing.trick();
        // 소원은 Mahjong 을 막 낸 직후에만 가능. 가장 마지막 플레이가 Mahjong 인지 확인.
        if (trick.currentTop() == null) {
            throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
        }
        List<Card> top = trick.currentTop().cards();
        boolean topIsMahjong = top.size() == 1 && top.get(0).is(Special.MAHJONG);
        if (!topIsMahjong || trick.currentTopSeat() != seat) {
            throw reject(RejectionReason.WISH_OUT_OF_CONTEXT);
        }
        if (trick.activeWish() != null) {
            throw reject(RejectionReason.DUPLICATE_DECLARATION, "wish already made this round");
        }
    }

```

→ **블록 삭제** (뒤따르는 빈 줄 포함).

- [ ] **Step 4: `TichuEngine` 에서 `MakeWish` 를 뺀다**

switch 에서 찾을 줄:

```java
            case TichuAction.MakeWish w -> applyMakeWish(state, seat, w);
```

→ **줄 삭제**.

`applyMakeWish` 메서드 전체를 찾는다:

```java
    // ---------- Wish ----------

    private Result applyMakeWish(TichuState state, int seat, TichuAction.MakeWish action) {
        TichuState.Playing playing = (TichuState.Playing) state;
        TrickState trick = playing.trick();
        TrickState updated = new TrickState(
                trick.leadSeat(), trick.currentTurnSeat(), trick.currentTop(),
                trick.currentTopSeat(), trick.passedSeats(), trick.playSequence(),
                trick.accumulatedCards(), Wish.active(action.rank()));
        return new Result(
                new TichuState.Playing(playing.players(), updated, playing.firstFinisher()),
                List.of(new TichuEvent.WishMade(action.rank())));
    }

```

→ **블록 삭제** (`// ---------- Wish ----------` 헤더와 뒤따르는 빈 줄 포함).

- [ ] **Step 5: 컴파일 + 잔여 참조 확인**

Run:

```bash
./gradlew :server:compileJava :server:compileTestJava -q && grep -rn "MakeWish\|MAKE_WISH" server/src
```

Expected: 컴파일 성공, grep 결과 **0건**. 컴파일 에러가 나면 놓친 참조가 있다는 뜻이므로
에러가 가리키는 파일을 고친다 (sealed switch 는 누락 케이스를 컴파일러가 잡아 준다).

- [ ] **Step 6: 서버 전체 스위트를 돌린다**

Run:

```bash
./gradlew :server:test
```

Expected: PASS, 실패 0. (Testcontainers 통합 테스트가 있으므로 Docker 가 떠 있어야 한다 —
`docker compose up -d postgres redis` 는 불필요하고 Docker 데몬만 있으면 된다.)

- [ ] **Step 7: 커밋**

```bash
git add server/src
git commit -m "refactor(tichu): MAKE_WISH 액션 제거 — PlayCard 동봉으로 일원화 (D-108)"
```

---

## Task 4: 서버 — 봇이 소원을 후보로 낸다

**Files:**
- Modify: `server/src/main/java/com/mirboard/domain/game/tichu/bot/LegalActionEnumerator.java:95-98`
- Test: `server/src/test/java/com/mirboard/domain/game/tichu/bot/LegalActionEnumeratorTest.java`
- Create: `server/src/test/java/com/mirboard/domain/game/tichu/bot/TimeoutActionPolicyTest.java`

**Interfaces:**
- Consumes: Task 2 의 `PlayCard(cards)` / `PlayCard(cards, wishRank)`
- Produces: 마작 보유 좌석의 후보 목록에 마작 관련 `PlayCard` 가 14종 들어가고,
  **소원 없는 변형이 그중 가장 먼저** 온다. `TimeoutActionPolicy` 가 이 순서에 의존한다.

- [ ] **Step 1: 실패하는 후보 생성 테스트를 쓴다**

`LegalActionEnumeratorTest.java` 의 `playing_phase_not_my_turn_no_actions` **바로 위**에 추가한다.
`Card` import 는 이미 있다.

```java
    @Test
    void playing_phase_mahjong_offers_wish_variants_with_no_wish_first() {
        var players = List.of(
                p(0, Card.mahjong(), n(Suit.SWORD, 7)),
                p(1, n(Suit.JADE, 10)),
                p(2, n(Suit.STAR, 9)),
                p(3, n(Suit.PAGODA, 4)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        var legal = LegalActionEnumerator.enumerate(state, 0);

        var mahjongPlays = legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard pc
                        && pc.cards().size() == 1
                        && pc.cards().get(0).is(Special.MAHJONG))
                .map(a -> (TichuAction.PlayCard) a)
                .toList();

        // 소원 없음 1 + 랭크 2~14 의 13 = 14종.
        assertThat(mahjongPlays).hasSize(14);
        assertThat(mahjongPlays.get(0).wishRank()).isNull();
        assertThat(mahjongPlays.stream().map(TichuAction.PlayCard::wishRank).filter(r -> r != null))
                .containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        // 마작이 아닌 카드(SWORD 7)는 변형을 만들지 않는다.
        var nonMahjongPlays = legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard pc
                        && pc.cards().size() == 1
                        && !pc.cards().get(0).is(Special.MAHJONG))
                .toList();
        assertThat(nonMahjongPlays).hasSize(1);
    }
```

`Card` 는 이미 import 되어 있다. `Special` 은 없으므로 import 를 추가한다:

```java
import com.mirboard.domain.game.tichu.card.Special;
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.LegalActionEnumeratorTest"
```

Expected: FAIL — `Expected size: 14 but was: 1` (아직 변형을 만들지 않는다).

- [ ] **Step 3: 후보 생성에 소원 변형을 추가한다**

`LegalActionEnumerator.playingCandidates` 에서 찾을 내용:

```java
        // 1장 단일 플레이.
        for (Card c : me.hand()) {
            result.add(new TichuAction.PlayCard(List.of(c)));
        }
```

바꿀 내용:

```java
        // 1장 단일 플레이.
        for (Card c : me.hand()) {
            // 소원 없는 변형을 항상 **먼저** 넣는다 — TimeoutActionPolicy 가 동률에서
            // Stream.min 의 "먼저 온 것 유지" 성질로 고르므로, 이 순서라야 타임아웃
            // 자동 플레이가 D-108 이전과 똑같이 "소원 없이 마작" 으로 남는다.
            result.add(new TichuAction.PlayCard(List.of(c)));
            if (c.is(Special.MAHJONG)) {
                // 마작을 내는 액션에 소원을 동봉할 수 있다 (D-108). 봇은 휴리스틱 없이
                // 균등 후보 — RandomBotPolicy 가 이 중에서 고른다.
                for (int r = 2; r <= 14; r++) {
                    result.add(new TichuAction.PlayCard(List.of(c), r));
                }
            }
        }
```

`Special` 은 이미 import 되어 있다 (`isDragonGivePending` 이 쓴다).

- [ ] **Step 4: 후보 생성 테스트가 통과하는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.LegalActionEnumeratorTest"
```

Expected: PASS (기존 6개 테스트 포함 전부 그린).

- [ ] **Step 5: 타임아웃 동작 고정 테스트를 새로 쓴다**

`TimeoutActionPolicy` 에는 테스트가 없었다. Step 3 이 만든 순서 의존을 명시적으로 고정한다.

Create `server/src/test/java/com/mirboard/domain/game/tichu/bot/TimeoutActionPolicyTest.java`:

```java
package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-108 — 소원이 PlayCard 에 동봉되면서 봇 후보에 랭크 변형이 생겼다. 타임아웃
 * 자동 플레이는 **결정적**이어야 하므로 소원을 걸지 않는 쪽으로 고정한다.
 */
class TimeoutActionPolicyTest {

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    private static PlayerState p(int seat, Card... cards) {
        return PlayerState.initial(seat, List.of(cards));
    }

    @Test
    void timeout_on_mahjong_lead_plays_mahjong_without_wish() {
        var players = List.of(
                p(0, Card.mahjong(), n(Suit.SWORD, 7)),
                p(1, n(Suit.JADE, 10)),
                p(2, n(Suit.STAR, 9)),
                p(3, n(Suit.PAGODA, 4)));
        var state = new TichuState.Playing(players, TrickState.lead(0, null), -1);

        TichuAction chosen = TimeoutActionPolicy.choose(state, 0);

        assertThat(chosen).isInstanceOf(TichuAction.PlayCard.class);
        var play = (TichuAction.PlayCard) chosen;
        // 가장 약한 단일 = 마작(rank 1), 그리고 소원은 걸지 않는다.
        assertThat(play.cards()).containsExactly(Card.mahjong());
        assertThat(play.wishRank()).isNull();
    }
}
```

- [ ] **Step 6: 타임아웃 테스트를 돌린다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.TimeoutActionPolicyTest"
```

Expected: PASS. 실패하면 Step 3 에서 소원 없는 변형을 먼저 넣지 않은 것이다.

- [ ] **Step 7: 봇 풀매치가 교착 없이 끝나는지 확인한다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.domain.game.tichu.bot.*" --tests "*BotMatchSimulationIT"
```

Expected: PASS. 봇이 소원을 걸면 소원 강제가 실제로 작동하므로, 여기서 교착이 나면
`WishFulfillmentChecker` 의 출구(합법 액션 존재)가 보장되지 않는다는 뜻이다 — 그 경우
멈추고 보고한다 (임시로 소원 후보를 줄여 넘기지 말 것).

- [ ] **Step 8: 커밋**

```bash
git add server/src/main/java/com/mirboard/domain/game/tichu/bot/LegalActionEnumerator.java server/src/test/java/com/mirboard/domain/game/tichu/bot/
git commit -m "feat(tichu): 봇이 마작 소원을 후보로 낸다 — 타임아웃은 무소원 고정 (D-108)"
```

---

## Task 5: 클라 — 소원을 플레이에 동봉

**Files:**
- Modify: `client/src/types/tichu.ts:55-64`
- Modify: `client/src/i18n/messages.ts:82-85`
- Modify: `client/src/features/tichu/MakeWishModal.tsx`
- Modify: `client/src/features/tichu/useGameTableModel.ts`
- Modify: `client/src/features/tichu/useGameActions.ts`
- Modify: `client/src/features/tichu/useGameTableEffects.ts:19-71`
- Modify: `client/src/features/tichu/GameTable.tsx:83-116, 222-226`
- Test: `client/src/features/tichu/GameTable.test.tsx`

**Interfaces:**
- Consumes: Task 2 의 와이어 계약 — `{'@action': 'PLAY_CARD', cards, wishRank?}`
- Produces:
  - `useGameTableModel` 반환에 `pendingWishPlay: Card[] | null`, `setPendingWishPlay: (c: Card[] | null) => void`
    (`wishContextKey` · `showWishModal` · `setWishModalDismissed` 는 사라진다)
  - `useGameActions` 반환에 `handleConfirmWishPlay(rank: number)`,
    `handlePlayWithoutWish()`, `handleCancelWishPlay()`
    (`handleMakeWish` · `handleSkipWish` 는 사라진다)
  - `MakeWishModal` props: `{ open: boolean; onConfirm: (rank: number) => void; onSkipWish: () => void; onCancel: () => void }`

- [ ] **Step 1: 실패하는 UI 테스트를 쓴다**

`GameTable.test.tsx` 의 `describe('GameTable — 매치 종료', ...)` **바로 위**에 추가한다.

```tsx
const MAHJONG: Card = { suit: null, rank: 1, special: 'MAHJONG' };

describe('GameTable — 마작 소원 동봉 (D-108)', () => {
  it('마작이 포함된 선택을 내면 전송 전에 소원 모달을 띄운다', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING', currentTurnSeat: 0 }),
      privateHand: privateHand([MAHJONG, card('JADE', 5)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'MAHJONG 1' }));
    fireEvent.click(screen.getByRole('button', { name: '내기 (1장)' }));

    // 아직 아무것도 나가지 않았다.
    expect(sendAction).not.toHaveBeenCalled();
    expect(screen.getByText('마작을 냅니다 — 소원을 지정할까요?')).toBeInTheDocument();
  });

  it('소원 지정하고 내기 → wishRank 를 동봉한 PLAY_CARD 한 번', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING', currentTurnSeat: 0 }),
      privateHand: privateHand([MAHJONG, card('JADE', 5)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'MAHJONG 1' }));
    fireEvent.click(screen.getByRole('button', { name: '내기 (1장)' }));
    fireEvent.click(screen.getByRole('button', { name: '7' }));
    fireEvent.click(screen.getByRole('button', { name: '소원 지정하고 내기' }));

    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      cards: [MAHJONG],
      wishRank: 7,
    });
  });

  it('소원 없이 내기 → wishRank 없는 PLAY_CARD 한 번', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING', currentTurnSeat: 0 }),
      privateHand: privateHand([MAHJONG, card('JADE', 5)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'MAHJONG 1' }));
    fireEvent.click(screen.getByRole('button', { name: '내기 (1장)' }));
    fireEvent.click(screen.getByRole('button', { name: '소원 없이 내기' }));

    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      cards: [MAHJONG],
    });
  });

  it('esc 로 모달을 닫으면 아무것도 보내지 않고 선택을 유지한다', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING', currentTurnSeat: 0 }),
      privateHand: privateHand([MAHJONG, card('JADE', 5)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'MAHJONG 1' }));
    fireEvent.click(screen.getByRole('button', { name: '내기 (1장)' }));
    // Radix Dialog 는 ownerDocument 에서 Escape 를 듣는다.
    fireEvent.keyDown(document, { key: 'Escape', code: 'Escape' });

    expect(sendAction).not.toHaveBeenCalled();
    expect(useTichuStore.getState().selectedCardKeys.size).toBe(1);
  });

  it('마작이 없는 선택은 모달 없이 즉시 PLAY_CARD 를 보낸다', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING', currentTurnSeat: 0 }),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 9)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'JADE 5' }));
    fireEvent.click(screen.getByRole('button', { name: '내기 (1장)' }));

    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      cards: [card('JADE', 5)],
    });
  });
});
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run:

```bash
npm --prefix client run test -- GameTable
```

Expected: FAIL — 새 5개 케이스가 `Unable to find an element with the text: 마작을 냅니다 — 소원을 지정할까요?`
등으로 실패한다 (마지막 "마작이 없는 선택" 케이스만 기존 동작이라 통과할 수 있다).

- [ ] **Step 3: i18n 문구를 바꾼다**

`client/src/i18n/messages.ts` 에서 찾을 내용:

```ts
  'wish.title': '소원 — 다음에 강제할 랭크',
  'wish.body': '다른 플레이어가 가능한 한 이 랭크 카드를 포함하도록 강제합니다. 건너뛰면 소원 없음.',
  'wish.skip': '건너뛰기 (소원 없음)',
  'wish.confirm': '소원 지정',
```

바꿀 내용:

```ts
  'wish.title': '마작을 냅니다 — 소원을 지정할까요?',
  'wish.body': '지정한 랭크를 다른 플레이어가 가능한 한 포함하도록 강제합니다. 소원은 마작을 내는 순간 함께 정해집니다.',
  'wish.skip': '소원 없이 내기',
  'wish.confirm': '소원 지정하고 내기',
```

- [ ] **Step 4: `MakeWishModal` props 를 3분기로 나눈다**

`MakeWishModal.tsx` 전체를 다음으로 바꾼다:

```tsx
import { useState } from 'react';
import { t } from '@/i18n/messages';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { rankGlyph } from './handType';

interface MakeWishModalProps {
  open: boolean;
  /** 소원을 지정하고 낸다. */
  onConfirm: (rank: number) => void;
  /** 소원 없이 낸다. */
  onSkipWish: () => void;
  /** 취소 — 아무것도 보내지 않는다 (esc / 바깥 클릭). */
  onCancel: () => void;
}

const RANKS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

/**
 * 소원 모달. D-108 이후 이 모달은 "이미 낸 마작에 소원을 건다"가 아니라 **아직 보내지
 * 않은 플레이에 소원을 실을지 묻는** 창이다. 그래서 dismiss(esc/바깥 클릭)는 "소원
 * 없이 내기"가 아니라 **취소**다 — 무심코 닫았을 때 카드가 나가면 안 된다.
 */
export function MakeWishModal({ open, onConfirm, onSkipWish, onCancel }: MakeWishModalProps) {
  const [selected, setSelected] = useState<number | null>(null);

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) {
          setSelected(null);
          onCancel();
        }
      }}
    >
      <DialogContent className="app-shell">
        <DialogHeader>
          <DialogTitle>{t('wish.title')}</DialogTitle>
          <DialogDescription>{t('wish.body')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-7 gap-2">
          {RANKS.map((r) => (
            <Button
              key={r}
              type="button"
              variant={selected === r ? 'default' : 'outline'}
              size="sm"
              onClick={() => setSelected(r)}
            >
              {rankGlyph(r)}
            </Button>
          ))}
        </div>
        <DialogFooter className="gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setSelected(null);
              onSkipWish();
            }}
          >
            {t('wish.skip')}
          </Button>
          <Button
            type="button"
            disabled={selected === null}
            onClick={() => {
              if (selected === null) return;
              const rank = selected;
              setSelected(null);
              onConfirm(rank);
            }}
          >
            {t('wish.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 5: `useGameTableModel` 의 소원 상태를 대기 플레이로 교체한다**

`useGameTableModel.ts` 에서 파일 상단 주석을 찾는다:

```ts
 * 유일한 예외가 소원 모달 dismiss 플래그인데, 모달 표시 여부(showWishModal)를
 * 계산하려면 같은 자리에 있어야 해서 함께 둔다.
```

바꿀 내용:

```ts
 * 유일한 예외가 소원 모달의 대기 플레이(pendingWishPlay)인데, 모달 표시 여부를
 * 여기서 계산하므로 같은 자리에 둔다.
```

상태 선언을 찾는다:

```ts
  const [wishModalDismissed, setWishModalDismissed] = useState(false);
```

바꿀 내용:

```ts
  // D-108 — 마작이 포함된 플레이는 소원 모달을 먼저 거친다. 여기 담겨 있는 동안은
  // 아직 서버로 나가지 않은 상태이고, 모달을 취소하면 그대로 폐기된다.
  const [pendingWishPlay, setPendingWishPlay] = useState<Card[] | null>(null);
```

파생값 블록을 찾는다:

```ts
  const myMahjongLeadActive =
    isInPlaying &&
    tableView !== null &&
    tableView.currentTopSeat === mySeat &&
    tableView.currentTop !== null &&
    tableView.currentTop.cards.length === 1 &&
    tableView.currentTop.cards[0].special === 'MAHJONG' &&
    tableView.activeWishRank === null;

  const wishContextKey = myMahjongLeadActive
    ? `${tableView.currentTopSeat}-mahjong`
    : null;

  const showWishModal = myMahjongLeadActive && !wishModalDismissed;

```

→ **블록 삭제** (뒤따르는 빈 줄 포함). 이 셋이 원 버그의 진원지다.

반환 객체에서 찾을 내용:

```ts
    wishContextKey,
    showWishModal,
    setWishModalDismissed,
```

바꿀 내용:

```ts
    pendingWishPlay,
    setPendingWishPlay,
```

- [ ] **Step 6: `useGameActions` 에 3분기 핸들러를 넣는다**

`useGameActions.ts` 의 args 인터페이스에서 찾을 줄:

```ts
  setWishModalDismissed: (v: boolean) => void;
```

바꿀 내용:

```ts
  /** 아직 보내지 않은, 소원 모달 대기 중인 플레이. null 이면 모달은 닫혀 있다. */
  pendingWishPlay: Card[] | null;
  setPendingWishPlay: (cards: Card[] | null) => void;
```

구조분해 파라미터에서 찾을 줄:

```ts
  setWishModalDismissed,
```

바꿀 내용:

```ts
  pendingWishPlay,
  setPendingWishPlay,
```

`handlePlay` 를 찾는다:

```ts
  function handlePlay() {
    if (selectedCards.length === 0) {
      setError(t('play.error.pickCard'));
      return;
    }
    sendAction({ '@action': 'PLAY_CARD', cards: selectedCards });
    clearSelection();
  }
```

바꿀 내용:

```ts
  function handlePlay() {
    if (selectedCards.length === 0) {
      setError(t('play.error.pickCard'));
      return;
    }
    // D-108 — 마작이 포함되면 소원을 먼저 묻고 한 프레임으로 보낸다. 여기서는 아직
    // 전송하지 않는다. 소원은 마작을 내는 행위의 일부라 서버도 한 액션으로 받는다.
    if (selectedCards.some((c) => c.special === 'MAHJONG')) {
      setPendingWishPlay(selectedCards);
      return;
    }
    sendAction({ '@action': 'PLAY_CARD', cards: selectedCards });
    clearSelection();
  }
```

`handleMakeWish` / `handleSkipWish` 를 찾는다:

```ts
  function handleMakeWish(rank: number) {
    sendAction({ '@action': 'MAKE_WISH', rank });
    setWishModalDismissed(true);
  }

  function handleSkipWish() {
    setWishModalDismissed(true);
  }
```

바꿀 내용:

```ts
  /** 소원을 지정하고 낸다 — PLAY_CARD 한 프레임에 wishRank 를 동봉. */
  function handleConfirmWishPlay(rank: number) {
    if (!pendingWishPlay) return;
    sendAction({ '@action': 'PLAY_CARD', cards: pendingWishPlay, wishRank: rank });
    setPendingWishPlay(null);
    clearSelection();
  }

  /** 소원 없이 낸다 — 일반 PLAY_CARD 와 동일한 프레임. */
  function handlePlayWithoutWish() {
    if (!pendingWishPlay) return;
    sendAction({ '@action': 'PLAY_CARD', cards: pendingWishPlay });
    setPendingWishPlay(null);
    clearSelection();
  }

  /** 취소 — 전송하지 않고 선택도 유지한다(다시 "내기" 를 누를 수 있게). */
  function handleCancelWishPlay() {
    setPendingWishPlay(null);
  }
```

반환 객체에서 찾을 내용:

```ts
    handleMakeWish,
    handleSkipWish,
```

바꿀 내용:

```ts
    handleConfirmWishPlay,
    handlePlayWithoutWish,
    handleCancelWishPlay,
```

- [ ] **Step 7: `useGameTableEffects` 에서 죽은 리셋 effect 를 지운다**

args 인터페이스에서 찾을 내용:

```ts
  /** Mahjong 리드 컨텍스트 식별자. 바뀌면 소원 모달 dismiss 를 초기화한다. */
  wishContextKey: string | null;
  setWishModalDismissed: (v: boolean) => void;
```

→ **삭제**.

JSDoc 에서 찾을 줄:

```ts
 * GameTable 의 부수효과 묶음 — 소원 모달 초기화, 매치 종료/내 차례 연출 트리거,
```

바꿀 내용:

```ts
 * GameTable 의 부수효과 묶음 — 매치 종료/내 차례 연출 트리거,
```

구조분해 파라미터에서 찾을 내용:

```ts
  wishContextKey,
  setWishModalDismissed,
```

→ **삭제**.

첫 effect 를 찾는다:

```ts
  useEffect(() => {
    setWishModalDismissed(false);
    // setWishModalDismissed 는 setState 라 안정적 — wishContextKey 변화로만 트리거.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wishContextKey]);

```

→ **블록 삭제** (뒤따르는 빈 줄 포함). 남은 effect 들의 **상대 순서는 그대로** 유지한다
(파일 주석의 "재배치 금지" 규칙).

- [ ] **Step 8: `GameTable` 배선을 바꾼다**

`useGameTableEffects` 호출 인자에서 찾을 내용:

```tsx
    wishContextKey: m.wishContextKey,
    setWishModalDismissed: m.setWishModalDismissed,
```

→ **삭제**.

`useGameActions` 호출 인자에서 찾을 내용:

```tsx
    setWishModalDismissed: m.setWishModalDismissed,
```

바꿀 내용:

```tsx
    pendingWishPlay: m.pendingWishPlay,
    setPendingWishPlay: m.setPendingWishPlay,
```

모달 렌더를 찾는다:

```tsx
      <MakeWishModal
        open={m.showWishModal}
        onConfirm={a.handleMakeWish}
        onSkip={a.handleSkipWish}
      />
```

바꿀 내용:

```tsx
      <MakeWishModal
        open={m.pendingWishPlay !== null}
        onConfirm={a.handleConfirmWishPlay}
        onSkipWish={a.handlePlayWithoutWish}
        onCancel={a.handleCancelWishPlay}
      />
```

- [ ] **Step 9: `types/tichu.ts` 에서 `MAKE_WISH` 를 뺀다**

찾을 줄:

```ts
  | 'MAKE_WISH'
```

→ **줄 삭제**.

- [ ] **Step 10: 타입 체크 + 테스트를 돌린다**

Run:

```bash
npm --prefix client run build:check && npm --prefix client run test -- GameTable
```

Expected: 타입 에러 0, GameTable 테스트 전부 PASS (기존 11건 + 신규 5건).

- [ ] **Step 11: 클라 전체 스위트를 돌린다**

Run:

```bash
npm --prefix client run test && npm --prefix client run build
```

Expected: PASS, 빌드 성공. `tichuStore.applyEvent.test.ts` 의 `WISH_MADE` /
`TRICK_TAKEN` 케이스가 **무변경으로** 통과해야 한다 — 이벤트 수신 경로는 안 건드렸다는 증거다.

- [ ] **Step 12: 잔여 참조 확인**

Run:

```bash
grep -rn "MAKE_WISH\|wishModalDismissed\|showWishModal\|wishContextKey\|myMahjongLeadActive\|handleMakeWish\|handleSkipWish" client/src
```

Expected: **0건**.

- [ ] **Step 13: 커밋**

```bash
git add client/src
git commit -m "feat(client): 마작 소원을 PLAY_CARD 에 동봉 — 모달 dismiss 상태머신 제거 (D-108)"
```

---

## Task 6: 통합 검증

**Files:**
- Modify: `server/src/test/java/com/mirboard/infra/ws/GameStompControllerIntegrationTest.java:82, 125-148`
- Modify: `docs/plans/tichu-wish-with-playcard.md` §8 (실행 결과 반영)

**Interfaces:**
- Consumes: Task 2~5 전부
- Produces: 없음 (검증 전용)

- [ ] **Step 1: 기존 와이어 테스트를 소원 동봉으로 확장한다**

`GameStompControllerIntegrationTest` 에는 이미
`mahjong_leader_plays_and_event_broadcasts_to_subscribers` 가 있고,
`forcePlayingFromDealing` 이 **마작 보유자를 리드 좌석으로 결정론적으로** 만든다.
새 테스트를 만들어 40줄짜리 셋업을 복제하지 말고 **이 테스트를 확장**한다 —
같은 프레임에 `wishRank` 를 실으면 기존 `PLAYED` 검증을 유지한 채 D-108 와이어 계약이
함께 덮인다.

메서드 이름을 찾는다:

```java
    void mahjong_leader_plays_and_event_broadcasts_to_subscribers() throws Exception {
```

바꿀 내용:

```java
    void mahjong_leader_plays_with_bundled_wish_and_events_broadcast() throws Exception {
```

액션 조립부에서 찾을 내용:

```java
        action.put("cards", List.of(cardJson));
        leaderSession.send("/app/room/" + roomId + "/action", action);
```

바꿀 내용:

```java
        action.put("cards", List.of(cardJson));
        // D-108 — 소원은 별도 액션이 아니라 이 프레임에 동봉된다.
        action.put("wishRank", 7);
        leaderSession.send("/app/room/" + roomId + "/action", action);
```

검증부에서 찾을 내용:

```java
        // Expect a Played event broadcast.
        JsonNode env = null;
        for (int i = 0; i < 5; i++) {
            JsonNode candidate = inbox.poll(2, TimeUnit.SECONDS);
            if (candidate != null && "PLAYED".equals(candidate.get("type").asText())) {
                env = candidate;
                break;
            }
        }
        assertThat(env).as("Subscriber must receive a PLAYED event").isNotNull();
        assertThat(env.get("payload").get("seat").asInt()).isEqualTo(leadSeat);
        assertThat(env.get("seq").asLong()).isPositive();
    }
```

바꿀 내용:

```java
        // 한 프레임이 PLAYED 와 WISH_MADE 를 함께 브로드캐스트한다 (D-108).
        JsonNode played = null;
        JsonNode wishMade = null;
        for (int i = 0; i < 8 && (played == null || wishMade == null); i++) {
            JsonNode candidate = inbox.poll(2, TimeUnit.SECONDS);
            if (candidate == null) continue;
            String type = candidate.get("type").asText();
            if ("PLAYED".equals(type)) played = candidate;
            if ("WISH_MADE".equals(type)) wishMade = candidate;
        }
        assertThat(played).as("Subscriber must receive a PLAYED event").isNotNull();
        assertThat(played.get("payload").get("seat").asInt()).isEqualTo(leadSeat);
        assertThat(played.get("seq").asLong()).isPositive();
        assertThat(wishMade).as("같은 프레임이 WISH_MADE 도 낸다 (D-108)").isNotNull();
        assertThat(wishMade.get("payload").get("rank").asInt()).isEqualTo(7);
    }
```

- [ ] **Step 2: 와이어 테스트를 돌린다**

Run:

```bash
./gradlew :server:test --tests "com.mirboard.infra.ws.GameStompControllerIntegrationTest"
```

Expected: PASS. Docker 데몬이 떠 있어야 한다 (Testcontainers).
`WISH_MADE` 가 안 오면 Task 2 Step 8 의 엔진 변경이 빠진 것이고, 프레임이 거절되면
(`ERROR` 만 옴) `wishRank` 역직렬화 — 즉 Task 2 Step 3 의 record 필드명을 확인한다.

- [ ] **Step 3: 설계 문서 §8 에 실행 결과를 반영한다**

`docs/plans/tichu-wish-with-playcard.md` §8 의 `GameStompControllerIntegrationTest` 행에서
"선결 확인" 조건부 서술을 실제 결과로 바꾼다. 찾을 내용:

```markdown
| `GameStompControllerIntegrationTest` | 와이어에서 `PLAY_CARD {cards, wishRank}` 한 프레임이 `PLAYED` + `WISH_MADE` 를 내는지. **선결 확인**: 마작 보유 좌석을 결정론적으로 만들 수 있는지 구현 착수 시 확인하고, 불가하면 엔진 레벨 검증으로 대체하되 그 사실을 이 문서에 남긴다 |
```

바꿀 내용:

```markdown
| `GameStompControllerIntegrationTest` | 와이어에서 `PLAY_CARD {cards, wishRank}` 한 프레임이 `PLAYED` + `WISH_MADE` 를 내는지. 기존 `mahjong_leader_plays_...` 테스트가 `forcePlayingFromDealing` 으로 마작 리더를 결정론적으로 만들고 있어, 셋업 복제 없이 그 테스트를 확장했다 |
```

- [ ] **Step 4: 서버 + 클라 전체 스위트를 돌린다**

Run:

```bash
./gradlew :server:test && npm --prefix client run test && npm --prefix client run build
```

Expected: 서버 실패 0, 클라 실패 0, 빌드 성공.

- [ ] **Step 5: 봇 스트레스로 룰 회귀를 본다**

Run:

```bash
./scripts/check.sh bot-stress 5
```

Expected: 5회 봇 풀매치 전부 완주 (`BotMatchSimulationIT` N회 시뮬레이션, ~5s/매치).
소원이 켜진 상태에서도 교착이 없어야 한다.

- [ ] **Step 6: 수동 QA — 원 결함 재현 경로**

```bash
./scripts/dev.sh up
```

서버·클라를 띄우고 `docs/qa-scenarios.md` **시나리오 1**(Task 1 에서 교체한 버전)을 그대로
수행한다. 특히 다음 두 가지를 확인한다:

1. 봇 방에서 마작을 리드했을 때 소원 모달이 **시간이 지나도 닫히지 않는다** (원 결함).
2. esc 로 닫으면 카드가 나가지 않는다.

- [ ] **Step 7: 포트 무변경 확인 (D-98 회귀 검증)**

Run:

```bash
git diff --stat main -- server/src/main/java/com/mirboard/infra server/src/main/java/com/mirboard/domain/game/core docs/game-port.md
```

Expected: **출력 없음** (변경 0). 이 작업은 게임 내부에서만 끝나야 한다. 변경이 있으면
설계가 틀린 것이므로 멈추고 보고한다.

- [ ] **Step 8: 최종 커밋**

```bash
git add -A
git commit -m "test: 마작 소원 동봉 통합 검증 + 미덮인 범위 기록 (D-108)"
```

(변경할 파일이 없으면 이 스텝은 건너뛴다.)

---

## 완료 기준

- [ ] `grep -rn "MAKE_WISH\|MakeWish" server/src` → **0건**
- [ ] `grep -rn "MAKE_WISH" client/src` → **0건**
      (`client/src/features/tichu/MakeWishModal.tsx` 의 컴포넌트 이름 `MakeWishModal` 은
      **남는다** — §6 "유지" 목록대로 모달 자체는 존치하고 props 만 바뀌므로, 제거된
      액션의 잔재가 아니다)
- [ ] `./gradlew :server:test` 실패 0
- [ ] `npm --prefix client run test` 실패 0, `npm --prefix client run build` 성공
- [ ] `git diff --stat main -- server/src/main/java/com/mirboard/infra` → 변경 0
- [ ] `docs/qa-scenarios.md` 시나리오 1 수동 통과 (모달이 닫히지 않음 + esc 취소)
- [ ] 봇 방에서 봇이 건 소원이 헤더 `활성 소원: N` 으로 보임
