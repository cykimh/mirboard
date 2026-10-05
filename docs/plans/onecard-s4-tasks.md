# 원카드 S4 구현 계획 — 클라 게임판 + 비공개 이벤트 비순번 (D-129)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원카드를 브라우저에서 끝까지 할 수 있게 한다 — 서버 손패 이벤트가 방 순번을 쓰지 않게 하고(D-126 의
`sequenced()`), 클라에 게임판·경쟁 버튼·튜토리얼을 붙인다. 카탈로그 열림(AVAILABLE) 전환은 D-116 병합 뒤 별건이라
이 계획이 끝나도 원카드는 `COMING_SOON` 이다.

**Architecture:** 스컬킹 게임판(D-103)과 같은 구조다 — 순수 리듀서 스토어(`onecardStore`, 순번 판정은 `useStompRoom`,
D-124) + 모듈 상수 sink(`onecardRoomSink`) + 자기 소켓을 가진 루트(`OneCardTable`). 손패는 `handVersion` 이 낮은 쪽을
버리고, 경쟁 버튼은 서버가 고른 슬롯·지터 자리에 화면(뷰포트) 기준으로 뜬다. 게임판 분기는 `RoomPage` 한 곳이고, 종료
화면을 가진 게임의 게임판 유지(D-120)를 스컬킹·원카드 공용으로 넓힌다. 서버는 `OneCardEvent.sequenced()` 재정의 한 곳만
바뀐다.

**Tech Stack:** React 18 + TypeScript + Zustand, Vitest + React Testing Library(jsdom), CSS part(`styles/parts/*`),
Java 25 + JUnit 5 + AssertJ(서버 1건).

**참고:** 설계 `docs/plans/onecard.md` §4.3·§4.5b·§4.10·§6 S4 행 · 룰 정본 `docs/rules-onecard.md` · 프로토콜 `docs/stomp-protocol.md`
원카드 절 · 본보기 `client/src/features/skullking/`(D-103·D-120·D-121). 이 계획의 코드는 검증용 스파이크에서 옮겼고, 스파이크에서
클라 553건·서버 1238건(실패 0)과 아래 각 단계의 실패·통과·테스트 수를 확인했다. 브라우저에서도 4인·2인 봇 판을 끝까지 돌려
게임판·7 무늬 고르기·공격·서버 거절 문구·경쟁 버튼 잡기·종료 화면·튜토리얼(데스크톱·모바일 375px, 라이트·다크)을 확인했다.

## Global Constraints

- 응답·주석·문서는 한국어.
- 작업 위치: 브랜치 `feat/onecard-s4`, 워크트리 `/Users/yupchang/Developer/mirboard/.claude/worktrees/onecard-plan`
  (main `cce26fb` 기반). 메인 체크아웃(`/Users/yupchang/Developer/mirboard`)과 다른 워크트리는 건드리지 않는다.
  Bash 는 매 호출 작업 폴더가 바뀔 수 있으니 **명령마다 워크트리 절대경로로 `cd` 하거나 `git -C` 를 쓴다.**
- 커밋 메시지 끝 줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` — 모델이 무엇이든 이 줄 그대로.
- 커밋 때 pre-commit 훅이 `scripts/check.sh fast`(클라 tsc + vitest + 서버 컴파일)를 돈다. `--no-verify` 금지. 그래서 **매 태스크가
  끝난 트리는 타입 검사와 클라 전체 테스트를 통과해야 한다.**
- 개발 서버(`bootRun`·`npm run dev`)를 띄우지 않는다 — 브라우저 확인은 Task 8 의 Phase Gate 에서 컨트롤러가 한다.
- **도메인 경계**: 인프라(`server/src/main/java/com/mirboard/infra`)는 바꾸지 않는다 —
  `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra` 가 0건이어야 한다. 클라 게임 중립 규약(D-103):
  `useStompRoom` 은 게임 스토어를 모르고, sink 는 모듈 상수이며 각 메서드는 호출 시점에 `getState()` 를 읽는다. 게임판 CSS 는
  `.oc-` 접두 전용 part 이고 공용 클래스(`.card-chip`·`.my-hand`·`.action-bar` …)를 단독으로 재정의하지 않는다(폭 미디어 0개,
  `17-responsive.css` 는 계속 마지막). 허브·대기실은 게임 튜토리얼 폴더를 직접 import 하지 않는다(`tutorialFor` 만).
- **사용자 결정(D-129)**: 카탈로그 열림(AVAILABLE) 전환은 이 계획에 넣지 않는다(D-116 병합 뒤 별건). 카드는 이미지 없이 CSS 로
  그린다. 튜토리얼은 규칙 10단계 + "낼 수 있을까?" 퀴즈 + 반응 연습(로컬 전용).
- 결정 번호: **D-129**. `docs/decisions.md` 의 `## D-128` 바로 위에 넣는다. 착수 시점에 D-129 도 쓰였으면 다음 빈 번호로 바꾸고
  본문의 번호를 모두 고친다.
- 테스트 명령: 클라 특정 파일 `npm --prefix client run test -- <이름 일부>`, 클라 전체 `npm --prefix client run test`, 타입 검사
  `npm --prefix client run build:check`. 서버 원카드 단위 `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`.
- 클라 테스트 수(누적, `npm --prefix client run test`의 마지막 두 줄): 기준 48파일·432건 → Task 2 49·446 → Task 3 51·483 → Task 4 56·515 →
  Task 5 57·542 → Task 6 58·551 → Task 7 58·553. 서버: 원카드 단위 176 → 178(Task 1), 전체 1236 → **1238**(Docker 불필요
  1037 → **1039**, 84%).
- **편집 표기**: ```` ```diff ```` 블록은 `-` 줄을 찾아 지우고 `+` 줄을 넣는다. 공백으로 시작하는 줄은 위치를 잡는 문맥이다
  (지우지 않는다). 세 경우 모두 첫 글자 하나를 떼고 읽는다(나머지 들여쓰기는 파일 그대로). `+` 뒤가 비어 있으면 빈 줄을 넣는다.
  한 블록은 파일에서 정확히 한 곳과 맞고, 블록은 적힌 순서대로 적용한다. 안에 ``` 가 든 블록은 네 개짜리 펜스(````)로 감쌌다 —
  그 ``` 줄은 파일 내용이다.

## 파일 지도

| 파일 | 태스크 | 책임 |
| --- | --- | --- |
| `server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java` | 1 | 손패 이벤트 2종 `sequenced()` = false |
| `client/src/types/onecard.ts` | 2 | 카드·뷰·이벤트 payload 타입, 표시 라벨(`cardLabel`·`cardKey`) |
| `client/src/features/onecard/onecardRules.ts` | 2 | 서버 `PlayRules`·`PlayingCard` 의 표시용 미러(`canPlay`·공격 값·세기·손패 정렬) |
| `client/src/features/onecard/onecardStore.ts` | 3 | 순수 리듀서 — 결과값 이벤트, `handVersion` 가드, 경쟁 누름 거절(`BUSY` 재시도·`NO_RACE` 늦었어요) |
| `client/src/features/onecard/onecardRoomSink.ts` | 3 | `RoomEventSink` 모듈 상수 — 비공개 큐(손패·ERROR) 라우팅과 문구 |
| `client/src/features/onecard/OneCardCardChip.tsx`, `client/src/features/onecard/raceSlots.ts` | 4 | CSS 카드 한 장 / 경쟁 버튼 슬롯 8개(프로토콜 상수)와 지터 |
| `client/src/features/onecard/tutorial/*` | 4 | 튜토리얼 12단계 · `PlayQuiz` · `ReactionPractice`(로컬 전용) |
| `client/src/features/tutorial/gameTutorials.ts`, `client/src/features/lobby/gameWiki.ts` | 4 | 튜토리얼 레지스트리·위키 링크에 원카드 |
| `client/src/components/seatOrder.ts`, `client/src/features/skullking/seatLayout.ts` | 5 | 좌석 순서·최소 폭을 공용으로(스컬킹은 다시 내보냄) |
| `client/src/features/onecard/OneCard{Seat,Center,Hand,MatchEnd,Table}.tsx`, `client/src/features/onecard/RaceButton.tsx` | 5 | 게임판 조각과 조립 루트(자기 소켓) |
| `client/src/styles/parts/19-onecard-table.css`, `index.css`, `cssSources.ts`, `17-responsive.css` | 6 | `.oc-` 게임판 CSS·포털 토큰·터치 하한 |
| `client/src/pages/RoomPage.tsx` | 7 | IN_GAME 분기에 원카드, 게임판 유지 공용화 |
| `docs/*`, `CLAUDE.md`, `README.md` | 1·8 | 결정 기록(1), 설계·프로토콜·룰·QA·수치(8) |

---

### Task 1: 서버 — 원카드 손패 이벤트는 순번을 쓰지 않는다

**Files:**
- Modify: `docs/decisions.md`(D-129), `server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java`
- Create: `server/src/test/java/com/mirboard/domain/game/onecard/event/OneCardEventSequencingTest.java`

**Interfaces:**
- Consumes: D-126 의 `GameEvent.sequenced()`(기본 true, false 면 브로드캐스터가 `seq` 를 붙이지 않는다), `GameEvent.isPrivate()`.
- Produces: `OneCardEvent.sequenced()` = `!isPrivate()` — `HAND_DEALT`·`HAND_UPDATED` 만 false.

- [ ] **Step 1: D-129 결정 기록** — `docs/decisions.md`

`docs/decisions.md`:

```diff
 보류 3건: ① 스컬킹 매치 결과 영속·ELO·desert_count 는 `users.rating` 단일 컬럼(D-02)의
 게임별 분리 결정이 선행이라 별건. ② 봇은 포트 기본(합법 균등 분포)으로 시작 — 휴리스틱은
 후속. ③ 카탈로그에 노출되지만 클라 게임판은 S6(D-103) — 직후 과제.
+
+## D-129 (2026-10-05) — 원카드 S4: 클라 게임판 + 비공개 이벤트 비순번 (열림 전환은 별건)
+
+원카드 손패 이벤트(`HAND_DEALT`·`HAND_UPDATED`)가 D-126 의 `GameEvent.sequenced()` 를 false 로 재정의해 방 순번을 쓰지
+않는다 — 내거나 먹을 때마다 다른 좌석이 resync 하던 구멍이 없어졌다(손패 순서는 `handVersion` 이 지킨다). 클라는 스컬킹과
+같은 구조(순수 리듀서 `onecardStore` + 모듈 상수 sink + 자기 소켓을 가진 `OneCardTable`)로 붙었고, 54장이 모두 달라 카드는
+값으로 고른다. 경쟁 버튼은 서버가 고른 슬롯·지터 자리에 **화면(뷰포트) 기준**으로 뜬다(설계 §4.10 의 "게임판 기준"을 바꿈 —
+게임판은 모바일에서 스크롤돼 버튼이 화면 밖에 뜰 수 있다). 자동 포커스·단축키 없이 aria-live 로 알리고, 락 경합 `BUSY` 는 창이
+열린 동안 2번까지 다시 보내며, 닫힌 창의 `NO_RACE` 는 "늦었어요"로 보인다. 종료 화면을 가진 게임의 게임판 유지(D-120)를
+스컬킹·원카드 공용으로 넓히고 좌석 순서 계산을 `components/seatOrder` 로 옮겼다. 계획 단계 사용자 결정: 카탈로그 열림
+(AVAILABLE) 전환은 D-116 병합 뒤 별건, 카드는 이미지 없이 CSS 로, 튜토리얼은 규칙 10단계 + "낼 수 있을까?" 퀴즈 + 반응 연습.
 
 ## D-128 (2026-10-05) — 원카드 S3: 포트 엔진 타이머 + 어댑터·봇·기록 (서버 완성, 클라 전 COMING_SOON)
 
```

- [ ] **Step 2: 실패하는 테스트 작성**

`server/src/test/java/com/mirboard/domain/game/onecard/event/OneCardEventSequencingTest.java`:

```java
package com.mirboard.domain.game.onecard.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-129 — 원카드 손패 이벤트는 방 순번을 쓰지 않는다. 쓰면 그 이벤트를 받지 않는 좌석에는 다음 공개 이벤트가 구멍이
 * 되어, 내거나 먹을 때마다 전원이 resync 했다. 공개 이벤트는 그대로 순번을 쓴다 — 클라는 공개 이벤트의 순번으로 중복과
 * 구멍을 판정한다(D-124).
 */
class OneCardEventSequencingTest {

    private static final PlayingCard HEART_5 = PlayingCard.of(Suit.HEART, 5);

    @Test
    void hand_events_are_private_and_unsequenced() {
        var dealt = new OneCardEvent.HandDealt(0, List.of(HEART_5), 1);
        var updated = new OneCardEvent.HandUpdated(1, List.of(HEART_5), List.of(HEART_5), 2);

        assertThat(dealt.isPrivate()).isTrue();
        assertThat(dealt.sequenced()).isFalse();
        assertThat(updated.isPrivate()).isTrue();
        assertThat(updated.sequenced()).isFalse();
    }

    @Test
    void public_events_stay_sequenced() {
        List<OneCardEvent> publicEvents = List.of(
                new OneCardEvent.MatchStarted(0, HEART_5, 7, 39),
                new OneCardEvent.CardPlayed(0, HEART_5, null, 6, 0, 1),
                new OneCardEvent.CardsDrawn(1, 2, OneCardEvent.DrawReason.ATTACK, 9, 30),
                new OneCardEvent.PileReshuffled(40),
                new OneCardEvent.TurnChanged(1, 1, 0),
                new OneCardEvent.RaceOpened(7, 0, 3, -20, 40, 3000),
                new OneCardEvent.RaceResolved(7, OneCardEvent.RaceOutcome.CALLED, 0),
                new OneCardEvent.PlayerEliminated(2, Elimination.Reason.BANKRUPT, 20, 25),
                new OneCardEvent.MatchEnded(MatchResult.EndReason.FINISHED, List.of(
                        new MatchResult.Standing(0, 1, 0, MatchResult.SeatStatus.FINISHED))));

        assertThat(publicEvents).hasSize(9).allSatisfy(e -> {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
        });
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.event.OneCardEventSequencingTest"`
Expected: FAIL — `2 tests completed, 1 failed`(`hand_events_are_private_and_unsequenced` — 기본값이 true 라 `sequenced()` 가 true 다).

- [ ] **Step 4: 구현**

`server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java`:

```diff
             case HandUpdated updated -> updated.seat();
             default -> -1;
         };
+    }
+
+    /**
+     * D-129 — 손패를 담은 비공개 이벤트는 방 순번을 쓰지 않는다(D-126 의 포트 확장). 쓰면 그 이벤트를 받지 않는
+     * 좌석에는 다음 공개 이벤트가 구멍으로 보인다 — 원카드는 내거나 먹을 때마다 {@code HAND_UPDATED} 가 나가므로
+     * 매 차례 전원이 resync 했다(설계서 §4.5b).
+     */
+    @Override
+    default boolean sequenced() {
+        return !isPrivate();
     }
 
     /** 먹은 이유 (§6.3, §7, §9.1). */
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :server:test --tests "com.mirboard.domain.game.onecard.*Test"`
Expected: PASS — 원카드 단위 178건(176 + 2).

- [ ] **Step 6: 커밋**

```bash
git add docs/decisions.md \
  server/src/main/java/com/mirboard/domain/game/onecard/event/OneCardEvent.java \
  server/src/test/java/com/mirboard/domain/game/onecard/event/OneCardEventSequencingTest.java
git commit -m "feat(D-129): 원카드 손패 이벤트는 방 순번을 쓰지 않는다 — sequenced() 재정의

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: 클라 — 원카드 타입과 규칙 미러

**Files:**
- Create: `client/src/types/onecard.ts`, `client/src/features/onecard/onecardRules.ts`, `client/src/features/onecard/onecardRules.test.ts`

**Interfaces:**
- Consumes: 서버 직렬화(`docs/stomp-protocol.md` 원카드 절) — `PlayingCard {suit, rank, joker}`, `tableView`·`privateHand`, 이벤트 payload.
- Produces: 타입 `OneCardCard`·`OneCardSuit`·`OneCardJoker`·`OneCardPhase`·`OneCardTableView`·`OneCardPrivateView`·`OneCardSeatView`·`OneCardRaceView`·
  `OneCardMatchResult`·`OneCardStanding`·`HandPayload`·`CardPlayedPayload`·`CardsDrawnPayload`·`TurnChangedPayload`·
  `PileReshuffledPayload`·`DrawReason`·`RaceOpenedPayload`·`RaceResolvedPayload`·`RaceOutcome`·`PlayerEliminatedPayload`·
  `MatchEndedPayload`·`EndReason`·`StandingStatus`·`EliminationReason`, 라벨 `SUIT_SYMBOL`·`SUIT_LABEL`·`JOKER_LABEL`·`rankLabel(rank)`·`cardLabel(card)`·
  `cardKey(card)`. 규칙 `attackValue(card)`·`isAttack(card)`·`attackStrength(card)`·`isSuitChange(card)`·
  `baseSuitOf(top, declaredSuit)`·`canPlay(card, top, declaredSuit, attackStack)`·`drawCount(attackStack)`·`sortForDisplay(hand)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/onecardRules.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import {
  attackStrength,
  attackValue,
  baseSuitOf,
  canPlay,
  drawCount,
  isAttack,
  isSuitChange,
  sortForDisplay,
} from './onecardRules';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';

/**
 * 서버 `PlayRules`·`PlayingCard` 의 클라 미러. 표시용이지만 틀리면 낼 수 있는 카드를 흐리게 그려 사람을 속이므로
 * `docs/rules-onecard.md` §5·§6.2 의 표를 그대로 옮겨 고정한다.
 */

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const BLACK: OneCardCard = { suit: null, rank: 0, joker: 'BLACK' };
const COLOR: OneCardCard = { suit: null, rank: 0, joker: 'COLOR' };

describe('공격 값과 세기 (§1·§6.2)', () => {
  it('2·A·흑백 조커·컬러 조커만 공격이고 값은 2·3·5·7 이다', () => {
    expect([c('HEART', 2), c('HEART', 1), BLACK, COLOR].map(attackValue)).toEqual([2, 3, 5, 7]);
    expect(isAttack(c('HEART', 7))).toBe(false);
    expect(isAttack(c('HEART', 13))).toBe(false);
  });

  it('세기는 2 < A < 흑백 조커 < 컬러 조커', () => {
    expect([c('CLUB', 2), c('CLUB', 1), BLACK, COLOR].map(attackStrength)).toEqual([1, 2, 3, 4]);
    expect(attackStrength(c('CLUB', 9))).toBe(0);
  });

  it('무늬 지정은 7 만', () => {
    expect(isSuitChange(c('SPADE', 7))).toBe(true);
    expect(isSuitChange(c('SPADE', 8))).toBe(false);
    expect(isSuitChange(BLACK)).toBe(false);
  });
});

describe('공격받는 중이 아닐 때 (§5.2)', () => {
  const top = c('HEART', 9);

  it('기준 무늬·같은 숫자·조커는 낼 수 있다', () => {
    expect(canPlay(c('HEART', 3), top, null, 0)).toBe(true);
    expect(canPlay(c('SPADE', 9), top, null, 0)).toBe(true);
    expect(canPlay(COLOR, top, null, 0)).toBe(true);
  });

  it('무늬도 숫자도 다르면 낼 수 없다 — 7 도 와일드가 아니다', () => {
    expect(canPlay(c('SPADE', 4), top, null, 0)).toBe(false);
    expect(canPlay(c('CLUB', 7), top, null, 0)).toBe(false);
  });

  it('7 로 지정한 무늬가 기준이 되고 숫자 일치는 그대로다', () => {
    const seven = c('HEART', 7);
    expect(baseSuitOf(seven, 'CLUB')).toBe('CLUB');
    expect(canPlay(c('CLUB', 4), seven, 'CLUB', 0)).toBe(true);
    expect(canPlay(c('HEART', 4), seven, 'CLUB', 0)).toBe(false);
    expect(canPlay(c('DIAMOND', 7), seven, 'CLUB', 0)).toBe(true);
  });

  it('맨 위가 조커면 아무 카드나 낼 수 있다', () => {
    expect(baseSuitOf(BLACK, null)).toBeNull();
    expect(canPlay(c('DIAMOND', 4), BLACK, null, 0)).toBe(true);
  });

  it('맨 위 카드가 아직 없으면 막지 않는다', () => {
    expect(canPlay(c('DIAMOND', 4), null, null, 0)).toBe(true);
  });
});

describe('공격받는 중일 때 (§5.3·§6.2 표)', () => {
  it('2 에는 모든 2·기준 무늬의 A·조커', () => {
    const top = c('HEART', 2);
    expect(canPlay(c('SPADE', 2), top, null, 2)).toBe(true);
    expect(canPlay(c('HEART', 1), top, null, 2)).toBe(true);
    expect(canPlay(c('SPADE', 1), top, null, 2)).toBe(false);
    expect(canPlay(BLACK, top, null, 2)).toBe(true);
    expect(canPlay(COLOR, top, null, 2)).toBe(true);
  });

  it('A 에는 모든 A·조커 — 2 는 약해서 안 된다', () => {
    const top = c('HEART', 1);
    expect(canPlay(c('CLUB', 1), top, null, 3)).toBe(true);
    expect(canPlay(c('HEART', 2), top, null, 3)).toBe(false);
    expect(canPlay(BLACK, top, null, 3)).toBe(true);
  });

  it('흑백 조커에는 컬러 조커만, 컬러 조커에는 아무것도', () => {
    expect(canPlay(COLOR, BLACK, null, 5)).toBe(true);
    expect(canPlay(c('HEART', 1), BLACK, null, 5)).toBe(false);
    expect(canPlay(BLACK, COLOR, null, 7)).toBe(false);
  });

  it('일반 카드와 7·J·Q·K 는 반격 수단이 아니다', () => {
    const top = c('HEART', 2);
    for (const rank of [3, 7, 11, 12, 13]) {
      expect(canPlay(c('HEART', rank), top, null, 2), String(rank)).toBe(false);
    }
  });
});

describe('먹기 장수 (§7)', () => {
  it('공격받는 중이면 누적, 아니면 1장', () => {
    expect(drawCount(0)).toBe(1);
    expect(drawCount(7)).toBe(7);
  });
});

describe('손패 표시 순서', () => {
  it('무늬(♠♥♦♣)별 숫자 오름차순, 조커는 맨 뒤 — 원본은 건드리지 않는다', () => {
    const hand = [COLOR, c('CLUB', 2), c('HEART', 13), BLACK, c('SPADE', 9), c('HEART', 1)];
    const sorted = sortForDisplay(hand);

    expect(sorted).toEqual([c('SPADE', 9), c('HEART', 1), c('HEART', 13), c('CLUB', 2), BLACK, COLOR]);
    expect(hand[0]).toBe(COLOR);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecardRules`
Expected: FAIL — `Error: Failed to resolve import "./onecardRules" from "src/features/onecard/onecardRules.test.ts". Does the file exist?`

- [ ] **Step 3: 구현 — 타입**

`client/src/types/onecard.ts`:

```ts
// 원카드 STOMP·resync 계약 — `docs/stomp-protocol.md` 원카드 절(D-128, D-129)과 일치.

export type OneCardSuit = 'SPADE' | 'HEART' | 'DIAMOND' | 'CLUB';
export type OneCardJoker = 'BLACK' | 'COLOR';

/** 서버 `PlayingCard` 직렬화 — 무늬 카드는 `joker` 가 null, 조커는 `suit` null·`rank` 0 이다. */
export interface OneCardCard {
  suit: OneCardSuit | null;
  rank: number;
  joker: OneCardJoker | null;
}

export type OneCardPhase = 'PLAYING' | 'RACE' | 'ENDED';
export type EliminationReason = 'BANKRUPT' | 'DESERTED';

export interface OneCardSeatView {
  seat: number;
  handCount: number;
  /** 탈락 사유, 살아 있으면 null (boolean 이 아니다). */
  eliminated: EliminationReason | null;
}

/** resync 의 경쟁 창 — 봇이 누를 시각은 싣지 않는다(State Hiding). */
export interface OneCardRaceView {
  raceId: number;
  ownerSeat: number;
  /** 0..7 — 슬롯 좌표는 클라 프로토콜 상수(`raceSlots.ts`). */
  slot: number;
  /** −100..100 — 슬롯 반경의 백분율. */
  jitterX: number;
  jitterY: number;
  windowMillis: number;
  /** 창 끝까지 남은 시간(서버 시계 기준). */
  remainingMillis: number;
}

export type EndReason = 'FINISHED' | 'LAST_STANDING' | 'NO_HUMANS' | 'STALEMATE';
export type StandingStatus = 'FINISHED' | 'ALIVE' | 'BANKRUPT' | 'DESERTED';

export interface OneCardStanding {
  seat: number;
  /** 1부터, 동순위 다음은 건너뛴다(1, 1, 3). */
  rank: number;
  /** 살아 있으면 남은 장수, 탈락했으면 탈락 순간의 장수. */
  cardsLeft: number;
  status: StandingStatus;
}

export interface OneCardMatchResult {
  reason: EndReason;
  standings: OneCardStanding[];
}

export interface OneCardTableView {
  phase: OneCardPhase;
  seats: OneCardSeatView[];
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  turnSeat: number;
  drawPileCount: number;
  race: OneCardRaceView | null;
  result: OneCardMatchResult | null;
}

export interface OneCardPrivateView {
  seat: number;
  hand: OneCardCard[];
  /** 상태 버전 — 비공개 이벤트의 `handVersion` 과 같은 축. 낮은 쪽을 버린다. */
  handVersion: number;
}

// ── 이벤트 payload ────────────────────────────────────────────────────

/** `HAND_DEALT`·`HAND_UPDATED` 공통 — 손패 **전체**와 버전. */
export interface HandPayload {
  seat: number;
  hand: OneCardCard[];
  handVersion: number;
  /** `HAND_UPDATED` 만 — 이번에 새로 받은 카드. */
  received?: OneCardCard[];
}

export interface CardPlayedPayload {
  seat: number;
  card: OneCardCard;
  declaredSuit: OneCardSuit | null;
  handCount: number;
  attackStack: number;
  direction: number;
}

export type DrawReason = 'TURN' | 'ATTACK' | 'PENALTY';

export interface CardsDrawnPayload {
  seat: number;
  count: number;
  reason: DrawReason;
  handCount: number;
  drawPileCount: number;
}

export interface PileReshuffledPayload {
  drawPileCount: number;
}

export interface TurnChangedPayload {
  seat: number;
  direction: number;
  attackStack: number;
}

export interface RaceOpenedPayload {
  raceId: number;
  ownerSeat: number;
  slot: number;
  jitterX: number;
  jitterY: number;
  windowMillis: number;
}

export type RaceOutcome = 'CALLED' | 'CAUGHT' | 'EXPIRED' | 'CANCELLED';

export interface RaceResolvedPayload {
  raceId: number;
  outcome: RaceOutcome;
  /** 누른 좌석, 아무도 안 눌렀으면 −1. */
  bySeat: number;
}

export interface PlayerEliminatedPayload {
  seat: number;
  reason: EliminationReason;
  cardsHeld: number;
  /** 탈락자 손패를 더미에 넣은 뒤 장수(최종값). */
  drawPileCount: number;
}

export type MatchEndedPayload = OneCardMatchResult;

// ── 표시 라벨 ─────────────────────────────────────────────────────────

export const SUIT_SYMBOL: Record<OneCardSuit, string> = {
  SPADE: '♠',
  HEART: '♥',
  DIAMOND: '♦',
  CLUB: '♣',
};

export const SUIT_LABEL: Record<OneCardSuit, string> = {
  SPADE: '스페이드',
  HEART: '하트',
  DIAMOND: '다이아',
  CLUB: '클로버',
};

export const JOKER_LABEL: Record<OneCardJoker, string> = {
  BLACK: '흑백 조커',
  COLOR: '컬러 조커',
};

/** 1→A, 11→J, 12→Q, 13→K. */
export function rankLabel(rank: number): string {
  switch (rank) {
    case 1:
      return 'A';
    case 11:
      return 'J';
    case 12:
      return 'Q';
    case 13:
      return 'K';
    default:
      return String(rank);
  }
}

/** 접근명·해설용 이름 — "하트 7", "흑백 조커". */
export function cardLabel(card: OneCardCard): string {
  if (card.joker) return JOKER_LABEL[card.joker];
  return `${SUIT_LABEL[card.suit!]} ${rankLabel(card.rank)}`;
}

/** 값 비교용 키 — 54장이 모두 다르므로 손패 안에서 유일하다. */
export function cardKey(card: OneCardCard): string {
  return card.joker ? `JOKER-${card.joker}` : `${card.suit}-${card.rank}`;
}
```

- [ ] **Step 4: 구현 — 규칙 미러**

`client/src/features/onecard/onecardRules.ts`:

```ts
import type { OneCardCard, OneCardSuit } from '@/types/onecard';

const SUIT_ORDER: Record<OneCardSuit, number> = { SPADE: 0, HEART: 1, DIAMOND: 2, CLUB: 3 };

/**
 * 원카드 카드 판정의 클라 미러 — 서버 `card/PlayingCard`·`rules/PlayRules` 와 같은 규칙이다
 * (`docs/rules-onecard.md` §1·§5·§6). **표시 전용**(낼 수 없는 카드 흐리게, 먹기 장수 안내)이고
 * 판정의 권위는 서버다.
 */

/** 먹일 장수 — 2 는 2, A 는 3, 흑백 조커 5, 컬러 조커 7, 그 밖은 0 (§1). */
export function attackValue(card: OneCardCard): number {
  if (card.joker === 'COLOR') return 7;
  if (card.joker === 'BLACK') return 5;
  if (card.rank === 2) return 2;
  return card.rank === 1 ? 3 : 0;
}

export function isAttack(card: OneCardCard): boolean {
  return attackValue(card) > 0;
}

/** 반격 세기 — 2 < A < 흑백 조커 < 컬러 조커 (§6.2). 공격 카드가 아니면 0. */
export function attackStrength(card: OneCardCard): number {
  if (card.joker === 'COLOR') return 4;
  if (card.joker === 'BLACK') return 3;
  if (card.rank === 1) return 2;
  return card.rank === 2 ? 1 : 0;
}

/** 7 — 낼 때 무늬를 지정한다 (§8.1). */
export function isSuitChange(card: OneCardCard): boolean {
  return card.joker === null && card.rank === 7;
}

/** 기준 무늬 (§5.1) — 7 로 지정된 무늬, 없으면 맨 위 카드의 무늬(조커면 null). */
export function baseSuitOf(
  top: OneCardCard | null,
  declaredSuit: OneCardSuit | null,
): OneCardSuit | null {
  return declaredSuit ?? top?.suit ?? null;
}

/**
 * 이 카드를 지금 낼 수 있는가 (§5.2·§5.3·§6.2). 공격받는 중이면 맨 위 공격 이상의 세기인 공격 카드만 —
 * 같은 숫자는 무늬와 무관하고, 다른 숫자는 기준 무늬가 같아야 하며, 조커는 무늬와 무관하다. 아니면 기준 무늬·
 * 같은 숫자·조커이고, 맨 위가 조커면 아무 카드나 된다. 7 은 와일드가 아니다.
 */
export function canPlay(
  card: OneCardCard,
  top: OneCardCard | null,
  declaredSuit: OneCardSuit | null,
  attackStack: number,
): boolean {
  if (top === null) return true;
  const base = baseSuitOf(top, declaredSuit);
  if (attackStack > 0) {
    if (!isAttack(card) || attackStrength(card) < attackStrength(top)) return false;
    if (card.joker) return true;
    return card.rank === top.rank || card.suit === base;
  }
  if (card.joker || top.joker) return true;
  return card.suit === base || card.rank === top.rank;
}

/** 지금 먹으면 몇 장인가 (§7) — 공격받는 중이면 누적, 아니면 1. */
export function drawCount(attackStack: number): number {
  return attackStack > 0 ? attackStack : 1;
}

/** 손패 표시 순서 — 무늬(♠♥♦♣)별 숫자 오름차순, 조커는 맨 뒤(흑백 → 컬러). 표시 전용이다. */
export function sortForDisplay(hand: OneCardCard[]): OneCardCard[] {
  const order = (c: OneCardCard) =>
    c.joker ? 100 + (c.joker === 'BLACK' ? 0 : 1) : SUIT_ORDER[c.suit!] * 20 + c.rank;
  return [...hand].sort((a, b) => order(a) - order(b));
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix client run test -- onecardRules`
Expected: PASS — 14건.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  49 passed (49)` · `Tests  446 passed (446)`.

- [ ] **Step 6: 커밋**

```bash
git add client/src/types/onecard.ts \
  client/src/features/onecard/onecardRules.ts \
  client/src/features/onecard/onecardRules.test.ts
git commit -m "feat(D-129): 원카드 클라 타입과 표시용 규칙 미러(낼 수 있는 카드·공격 세기·손패 정렬)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 클라 — 스토어(순수 리듀서)와 이벤트 수신부

**Files:**
- Create: `client/src/features/onecard/onecardStore.ts`, `client/src/features/onecard/onecardStore.test.ts`, `client/src/features/onecard/onecardRoomSink.ts`, `client/src/features/onecard/onecardRoomSink.test.ts`

**Interfaces:**
- Consumes: Task 2 타입·`cardKey`, `ws/roomEventSink.ts` 의 `RoomEventSink<TTable, TPrivate>`, `types/stomp.ts` 의 `ApplyEventResult`·
  `ResyncEnvelope`·`StompEnvelope`.
- Produces: `useOneCardStore`(상태 `roomId`·`phase`·`seats`·`topCard`·`declaredSuit`·`attackStack`·`direction`·`turnSeat`·`drawPileCount`·
  `race: OneCardClientRace | null`(`closesAt` = 이 클라 시계 기준 마감)·`lastRace`·`result`·`mySeat`·`hand`·`handVersion`·
  `selectedKey`·`suitChoice`·`press`·`retryNonce`·`raceNotice`·`disconnectedSeats`·`errorMessage`·`turnStartedAt`, 액션 `reset`·
  `applySnapshot`·`applyPrivateHand`·`applyEvent`·`setError`·`selectCard(key)`·`setSuitChoice(suit)`·`startPress(raceId, action)`·
  `notePressRejected('BUSY' | 'NO_RACE'): boolean`·`clearRaceNotice`), 타입 `OneCardRoomState`·`OneCardActions`·
  `OneCardClientRace`·`LastRace`·`PressAction`·`PendingPress`, 상수 `MAX_PRESS_ATTEMPTS`(3). `onecardRoomSink`(모듈 상수)·`onecardErrorLabels`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/onecardStore.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_PRESS_ATTEMPTS, useOneCardStore } from './onecardStore';
import {
  cardKey,
  type OneCardCard,
  type OneCardPrivateView,
  type OneCardSeatView,
  type OneCardSuit,
  type OneCardTableView,
} from '@/types/onecard';

const card = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const seat = (n: number, over: Partial<OneCardSeatView> = {}): OneCardSeatView => ({
  seat: n,
  handCount: 7,
  eliminated: null,
  ...over,
});

const TABLE: OneCardTableView = {
  phase: 'PLAYING',
  seats: [seat(0), seat(1), seat(2)],
  topCard: card('HEART', 9),
  declaredSuit: null,
  attackStack: 0,
  direction: 1,
  turnSeat: 1,
  drawPileCount: 32,
  race: null,
  result: null,
};

const PRIVATE: OneCardPrivateView = {
  seat: 1,
  hand: [card('HEART', 3), card('SPADE', 9), card('CLUB', 7)],
  handVersion: 4,
};

const snapshot = (
  over: Partial<{ tableView: OneCardTableView; privateHand: OneCardPrivateView | null }> = {},
) => ({
  roomId: 'r-1',
  phase: 'PLAYING',
  eventSeq: 10,
  tableView: TABLE,
  privateHand: PRIVATE,
  disconnectedSeats: [],
  chips: null,
  ...over,
});

const ev = (type: string, payload: unknown, seq?: number) => ({ type, seq, payload });
const store = () => useOneCardStore.getState();
const NOW = 1_000_000;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  store().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('applyEvent — 반환값 계약 (순번 판정은 훅, D-124)', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('리듀서가 있는 타입은 applied', () => {
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 40 }, 11))).toBe('applied');
  });

  it('순번을 판정하지 않는다 — 중복·구멍 판정은 훅 몫', () => {
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 40 }, 99))).toBe('applied');
    expect(store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 41 }, 3))).toBe('applied');
    expect(store().drawPileCount).toBe(41);
  });

  it('MATCH_STARTED(서버가 보내지 않는다)·모르는 타입은 unhandled — 훅이 resync 한다', () => {
    expect(store().applyEvent(ev('MATCH_STARTED', {}, 11))).toBe('unhandled');
    expect(store().applyEvent(ev('WHO_KNOWS', {}, 11))).toBe('unhandled');
  });
});

describe('applySnapshot — resync 는 권위값', () => {
  it('공개 상태를 그대로 미러하고 내 손패·좌석·버전을 받는다', () => {
    store().applySnapshot(snapshot());

    const s = store();
    expect(s.phase).toBe('PLAYING');
    expect(s.seats).toHaveLength(3);
    expect(s.topCard).toEqual(card('HEART', 9));
    expect(s.turnSeat).toBe(1);
    expect(s.drawPileCount).toBe(32);
    expect(s.mySeat).toBe(1);
    expect(s.hand).toHaveLength(3);
    expect(s.handVersion).toBe(4);
  });

  it('관전자는 privateHand 가 null — 좌석 없음·빈 손패', () => {
    store().applySnapshot(snapshot({ privateHand: null }));

    expect(store().mySeat).toBe(-1);
    expect(store().hand).toEqual([]);
  });

  it('열린 창은 남은 시간으로 이 클라 기준 마감 시각을 잡는다 — 봇 시각은 오지 않는다', () => {
    store().applySnapshot(
      snapshot({
        tableView: {
          ...TABLE,
          phase: 'RACE',
          turnSeat: -1,
          race: {
            raceId: 7,
            ownerSeat: 0,
            slot: 3,
            jitterX: -20,
            jitterY: 40,
            windowMillis: 3000,
            remainingMillis: 1200,
          },
        },
      }),
    );

    expect(store().race).toEqual({
      raceId: 7,
      ownerSeat: 0,
      slot: 3,
      jitterX: -20,
      jitterY: 40,
      windowMillis: 3000,
      closesAt: NOW + 1200,
    });
  });

  it('더 낮은 버전의 손패는 버린다 — 늦게 도착한 resync 가 새 손패를 되돌리지 않는다', () => {
    store().applySnapshot(snapshot());
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 6 });

    store().applySnapshot(snapshot({ tableView: { ...TABLE, drawPileCount: 30 } }));

    expect(store().hand).toEqual([card('HEART', 3)]);
    expect(store().handVersion).toBe(6);
    expect(store().drawPileCount).toBe(30);
  });

  it('끝난 판의 결과를 그대로 받는다 — 재접속해도 종료 화면이 남는다', () => {
    const result = {
      reason: 'FINISHED' as const,
      standings: [{ seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' as const }],
    };
    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'ENDED', result } }));

    expect(store().result).toEqual(result);
  });

  it('같은 창을 기다리던 누름만 남기고 오류 문구는 지운다', () => {
    const race = {
      raceId: 7,
      ownerSeat: 0,
      slot: 0,
      jitterX: 0,
      jitterY: 0,
      windowMillis: 3000,
      remainingMillis: 2000,
    };
    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', race } }));
    store().startPress(7, 'CATCH');
    store().setError('무언가');

    store().applySnapshot(snapshot({ tableView: { ...TABLE, phase: 'RACE', race } }));
    expect(store().press?.raceId).toBe(7);
    expect(store().errorMessage).toBeNull();

    store().applySnapshot(snapshot());
    expect(store().press).toBeNull();
  });
});

describe('applyPrivateHand — 손패 전체 + handVersion', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('손패를 통째로 바꾸고 버전을 올린다', () => {
    store().applyPrivateHand({
      seat: 1,
      hand: [card('HEART', 3), card('SPADE', 9), card('CLUB', 7), card('DIAMOND', 1)],
      received: [card('DIAMOND', 1)],
      handVersion: 5,
    });

    expect(store().hand).toHaveLength(4);
    expect(store().handVersion).toBe(5);
  });

  it('낮은 버전은 버리고, 같은 버전은 다시 적용해도 같다', () => {
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 3 });
    expect(store().hand).toHaveLength(3);

    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 4 });
    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 4 });
    expect(store().hand).toEqual([card('HEART', 3)]);
  });

  it('고른 카드가 새 손패에 남아 있으면 선택을 유지하고, 없으면 푼다', () => {
    store().selectCard(cardKey(card('SPADE', 9)));
    store().applyPrivateHand({
      seat: 1,
      hand: [...PRIVATE.hand, card('DIAMOND', 4)],
      handVersion: 5,
    });
    expect(store().selectedKey).toBe('SPADE-9');

    store().applyPrivateHand({ seat: 1, hand: [card('HEART', 3)], handVersion: 6 });
    expect(store().selectedKey).toBeNull();
  });

  it('매치가 끝난 뒤에도 내 손패는 받는다 — 판이 1라운드라 다음 라운드 손패가 섞일 일이 없다', () => {
    store().applyEvent(ev('MATCH_ENDED', { reason: 'FINISHED', standings: [] }, 11));
    store().applyPrivateHand({ seat: 1, hand: [], handVersion: 9 });

    expect(store().hand).toEqual([]);
  });
});

describe('공개 이벤트 — 결과값이라 두 번 적용해도 같다', () => {
  beforeEach(() => store().applySnapshot(snapshot()));

  it('CARD_PLAYED — 맨 위·지정 무늬·공격·방향·장수를 결과값으로, 차례는 다음 이벤트까지 비운다', () => {
    const played = ev(
      'CARD_PLAYED',
      { seat: 0, card: card('HEART', 7), declaredSuit: 'CLUB', handCount: 6, attackStack: 0, direction: 1 },
      11,
    );
    store().applyEvent(played);
    store().applyEvent(played);

    const s = store();
    expect(s.topCard).toEqual(card('HEART', 7));
    expect(s.declaredSuit).toBe('CLUB');
    expect(s.seats[0].handCount).toBe(6);
    expect(s.turnSeat).toBe(-1);
  });

  it('CARD_PLAYED — 지정 무늬가 없으면 이전 지정을 지운다', () => {
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 0, card: card('HEART', 7), declaredSuit: 'CLUB', handCount: 6, attackStack: 0, direction: 1 }, 11),
    );
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('CLUB', 2), declaredSuit: null, handCount: 2, attackStack: 2, direction: -1 }, 12),
    );

    expect(store().declaredSuit).toBeNull();
    expect(store().attackStack).toBe(2);
    expect(store().direction).toBe(-1);
  });

  it('내가 낸 카드면 선택을 푼다', () => {
    store().selectCard('HEART-3');
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 3), declaredSuit: null, handCount: 2, attackStack: 0, direction: 1 }, 11),
    );

    expect(store().selectedKey).toBeNull();
  });

  it('CARDS_DRAWN·PILE_RESHUFFLED — 장수와 뽑을 더미 장수', () => {
    store().applyEvent(
      ev('CARDS_DRAWN', { seat: 2, count: 2, reason: 'ATTACK', handCount: 9, drawPileCount: 30 }, 11),
    );
    expect(store().seats[2].handCount).toBe(9);
    expect(store().drawPileCount).toBe(30);

    store().applyEvent(ev('PILE_RESHUFFLED', { drawPileCount: 41 }, 12));
    expect(store().drawPileCount).toBe(41);
  });

  it('TURN_CHANGED — 차례·방향·공격 누적, 거절 문구를 지운다', () => {
    store().setError('지금 낼 수 없는 카드입니다.');
    store().applyEvent(ev('TURN_CHANGED', { seat: 2, direction: -1, attackStack: 5 }, 11));

    const s = store();
    expect(s.turnSeat).toBe(2);
    expect(s.direction).toBe(-1);
    expect(s.attackStack).toBe(5);
    expect(s.phase).toBe('PLAYING');
    expect(s.errorMessage).toBeNull();
  });

  it('RACE_OPENED — 창을 열고 차례를 비운다', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 10, jitterY: -10, windowMillis: 3000 }, 11),
    );

    const s = store();
    expect(s.phase).toBe('RACE');
    expect(s.turnSeat).toBe(-1);
    expect(s.race).toMatchObject({ raceId: 9, ownerSeat: 0, slot: 5, closesAt: NOW + 3000 });
  });

  it('RACE_RESOLVED — 지금 창이면 닫고 결과를 남긴다, 다른 창 번호면 열린 창은 그대로', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 11),
    );
    store().startPress(9, 'CATCH');

    store().applyEvent(ev('RACE_RESOLVED', { raceId: 8, outcome: 'EXPIRED', bySeat: -1 }, 12));
    expect(store().race?.raceId).toBe(9);
    expect(store().phase).toBe('RACE');

    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CAUGHT', bySeat: 1 }, 13));
    const s = store();
    expect(s.race).toBeNull();
    expect(s.phase).toBe('PLAYING');
    expect(s.press).toBeNull();
    expect(s.lastRace).toEqual({ raceId: 9, ownerSeat: 0, outcome: 'CAUGHT', bySeat: 1 });
  });

  it('다음 카드가 놓이면 지난 경쟁 결과 안내를 지운다', () => {
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 5, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 11),
    );
    store().applyEvent(ev('RACE_RESOLVED', { raceId: 9, outcome: 'CALLED', bySeat: 0 }, 12));
    store().applyEvent(
      ev('CARD_PLAYED', { seat: 1, card: card('HEART', 4), declaredSuit: null, handCount: 2, attackStack: 0, direction: 1 }, 13),
    );

    expect(store().lastRace).toBeNull();
  });

  it('PLAYER_ELIMINATED — 사유를 남기고 손패는 더미로(장수 0, 더미는 최종값)', () => {
    store().applyEvent(
      ev('PLAYER_ELIMINATED', { seat: 2, reason: 'BANKRUPT', cardsHeld: 21, drawPileCount: 25 }, 11),
    );

    expect(store().seats[2]).toEqual({ seat: 2, handCount: 0, eliminated: 'BANKRUPT' });
    expect(store().drawPileCount).toBe(25);
  });

  it('MATCH_ENDED — 결과를 남기고 창·차례·누름을 닫는다', () => {
    const result = {
      reason: 'LAST_STANDING',
      standings: [
        { seat: 0, rank: 1, cardsLeft: 3, status: 'ALIVE' },
        { seat: 1, rank: 2, cardsLeft: 0, status: 'DESERTED' },
      ],
    };
    store().applyEvent(ev('MATCH_ENDED', result, 11));

    const s = store();
    expect(s.result).toEqual(result);
    expect(s.phase).toBe('ENDED');
    expect(s.turnSeat).toBe(-1);
    expect(s.race).toBeNull();
  });

  it('끝난 뒤의 진행 이벤트는 ignored, 연결 상태 배지는 그대로 반영한다', () => {
    store().applyEvent(ev('MATCH_ENDED', { reason: 'FINISHED', standings: [] }, 11));

    expect(store().applyEvent(ev('TURN_CHANGED', { seat: 0, direction: 1, attackStack: 0 }, 12))).toBe('ignored');
    expect(store().turnSeat).toBe(-1);
    expect(store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 2 }))).toBe('applied');
    expect(store().disconnectedSeats.has(2)).toBe(true);
  });

  it('PLAYER_DISCONNECTED·PLAYER_RECONNECTED 가 배지를 켜고 끈다', () => {
    store().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 0 }));
    expect(store().disconnectedSeats.has(0)).toBe(true);
    store().applyEvent(ev('PLAYER_RECONNECTED', { seat: 0 }));
    expect(store().disconnectedSeats.has(0)).toBe(false);
  });
});

describe('경쟁 누름 — 거절 처리 (설계서 §4.4)', () => {
  const open = (windowMillis = 3000) =>
    store().applyEvent(
      ev('RACE_OPENED', { raceId: 9, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis }, 11),
    );

  beforeEach(() => store().applySnapshot(snapshot()));

  it('기다리는 누름이 없으면 false — 일반 오류로 보여 준다', () => {
    open();
    expect(store().notePressRejected('BUSY')).toBe(false);
    expect(store().notePressRejected('NO_RACE')).toBe(false);
  });

  it('NO_RACE 는 "늦었어요" — 누름을 내린다', () => {
    open();
    store().startPress(9, 'CATCH');

    expect(store().notePressRejected('NO_RACE')).toBe(true);
    expect(store().raceNotice).toBe('LATE');
    expect(store().press).toBeNull();

    store().clearRaceNotice();
    expect(store().raceNotice).toBeNull();
  });

  it('BUSY 는 창이 열려 있는 동안 최대 2회 재시도 신호를 올린다', () => {
    open();
    store().startPress(9, 'CALL_ONE_CARD');

    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().notePressRejected('BUSY')).toBe(true);
    expect(store().press?.attempts).toBe(MAX_PRESS_ATTEMPTS);
    expect(store().retryNonce).toBe(2);

    expect(store().notePressRejected('BUSY')).toBe(false);
    expect(store().press).toBeNull();
  });

  it('BUSY 라도 창이 이미 닫힐 시각이 지났으면 재시도하지 않는다', () => {
    open(1000);
    store().startPress(9, 'CATCH');
    vi.setSystemTime(NOW + 1500);

    expect(store().notePressRejected('BUSY')).toBe(false);
  });

  it('창이 새로 열리면 지난 누름과 안내를 지운다', () => {
    open();
    store().startPress(9, 'CATCH');
    store().notePressRejected('NO_RACE');

    store().applyEvent(
      ev('RACE_OPENED', { raceId: 10, ownerSeat: 2, slot: 1, jitterX: 0, jitterY: 0, windowMillis: 3000 }, 12),
    );
    expect(store().press).toBeNull();
    expect(store().raceNotice).toBeNull();
  });
});

describe('선택', () => {
  it('카드를 고르면 7 의 지정 무늬를 다시 고르게 한다', () => {
    store().applySnapshot(snapshot());
    store().selectCard('CLUB-7');
    store().setSuitChoice('HEART');
    expect(store().suitChoice).toBe('HEART');

    store().selectCard('SPADE-9');
    expect(store().selectedKey).toBe('SPADE-9');
    expect(store().suitChoice).toBeNull();
  });
});
```

`client/src/features/onecard/onecardRoomSink.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore } from './onecardStore';

/**
 * 원카드 비공개 큐 → 스토어. 손패 이벤트 두 종류는 같은 경로(`handVersion` 가드)로, `ERROR` 는 문구로, 경쟁
 * 누름의 거절은 재시도·"늦었어요"로 간다.
 */

const envelope = (type: string, payload: unknown) => ({ eventId: 'e', type, ts: 0, payload });
const errorEnvelope = (code: string, message = 'detail') => envelope('ERROR', { code, message });
const store = () => useOneCardStore.getState();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
  store().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('onecardRoomSink — 손패', () => {
  it('HAND_DEALT 와 HAND_UPDATED 가 같은 버전 가드로 손패를 바꾼다', () => {
    const heart = { suit: 'HEART', rank: 5, joker: null };
    onecardRoomSink.applyPrivateEvent(envelope('HAND_DEALT', { seat: 2, hand: [heart], handVersion: 1 }));
    expect(store().mySeat).toBe(2);
    expect(store().hand).toHaveLength(1);

    onecardRoomSink.applyPrivateEvent(
      envelope('HAND_UPDATED', { seat: 2, hand: [], received: [], handVersion: 3 }),
    );
    onecardRoomSink.applyPrivateEvent(
      envelope('HAND_UPDATED', { seat: 2, hand: [heart, heart], received: [heart], handVersion: 2 }),
    );
    expect(store().hand).toEqual([]);
    expect(store().handVersion).toBe(3);
  });
});

describe('onecardRoomSink — ERROR 문구', () => {
  it('라벨이 있는 코드는 한국어 문구로 보여 준다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('COUNTER_REQUIRED'));

    expect(store().errorMessage).toBe(
      '공격받는 중에는 반격 카드만 낼 수 있습니다. 반격할 수 없으면 먹으세요.',
    );
  });

  it('라벨이 없는 코드는 코드와 원문을 그대로 보여 준다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('SOMETHING_NEW', 'detail'));

    expect(store().errorMessage).toBe('SOMETHING_NEW: detail');
  });

  it('기다리는 누름이 없으면 BUSY 도 일반 오류다 — 카드를 낼 때의 락 경합', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBe('다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.');
  });
});

describe('onecardRoomSink — 경쟁 누름의 거절', () => {
  beforeEach(() => {
    store().applyEvent({
      type: 'RACE_OPENED',
      payload: { raceId: 4, ownerSeat: 0, slot: 0, jitterX: 0, jitterY: 0, windowMillis: 3000 },
    });
    store().startPress(4, 'CATCH');
  });

  it('BUSY 는 오류를 띄우지 않고 재시도 신호를 올린다', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('BUSY'));

    expect(store().errorMessage).toBeNull();
    expect(store().retryNonce).toBe(1);
  });

  it('NO_RACE 는 오류 대신 "늦었어요"', () => {
    onecardRoomSink.applyPrivateEvent(errorEnvelope('NO_RACE'));

    expect(store().errorMessage).toBeNull();
    expect(store().raceNotice).toBe('LATE');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecardStore onecardRoomSink`
Expected: FAIL — `Test Files  2 failed (2)`. 두 파일 모두 import 를 못 찾는다(스토어 테스트 `Failed to resolve import "./onecardStore"`,
수신부 테스트 `Failed to resolve import "./onecardRoomSink"`).

- [ ] **Step 3: 구현 — 스토어**

`client/src/features/onecard/onecardStore.ts`:

```ts
import { create } from 'zustand';
import type { ApplyEventResult, ResyncEnvelope } from '@/types/stomp';
import {
  cardKey,
  type CardPlayedPayload,
  type CardsDrawnPayload,
  type HandPayload,
  type MatchEndedPayload,
  type OneCardCard,
  type OneCardMatchResult,
  type OneCardPhase,
  type OneCardPrivateView,
  type OneCardSeatView,
  type OneCardSuit,
  type OneCardTableView,
  type PileReshuffledPayload,
  type PlayerEliminatedPayload,
  type RaceOpenedPayload,
  type RaceOutcome,
  type RaceResolvedPayload,
  type TurnChangedPayload,
} from '@/types/onecard';

/** 경쟁 창 — 서버 값에 이 클라 시계 기준 마감 시각을 더한다(진행 막대·BUSY 재시도 판단). */
export interface OneCardClientRace {
  raceId: number;
  ownerSeat: number;
  slot: number;
  jitterX: number;
  jitterY: number;
  windowMillis: number;
  closesAt: number;
}

/** 방금 닫힌 경쟁 — 가운데 안내 한 줄. 다음 카드가 놓이면 지운다. */
export interface LastRace {
  raceId: number;
  ownerSeat: number;
  outcome: RaceOutcome;
  bySeat: number;
}

export type PressAction = 'CALL_ONE_CARD' | 'CATCH';

/** 내가 보낸 누름 — 응답(창 해소·거절)을 기다리는 동안만 있다. */
export interface PendingPress {
  raceId: number;
  action: PressAction;
  attempts: number;
}

/** 첫 누름 + 재시도 2회 (설계서 §4.4 — 락 경합 `BUSY` 는 창이 열린 동안 최대 2회 재시도). */
export const MAX_PRESS_ATTEMPTS = 3;

export interface OneCardRoomState {
  roomId: string | null;

  // ── 공개 상태 (tableView 미러, 결과값) ──
  phase: OneCardPhase | null;
  seats: OneCardSeatView[];
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  /** 차례 좌석 — 경쟁 창이 열렸거나 끝났으면 −1. */
  turnSeat: number;
  drawPileCount: number;
  race: OneCardClientRace | null;
  lastRace: LastRace | null;
  result: OneCardMatchResult | null;

  // ── 본인 전용 ──
  mySeat: number;
  hand: OneCardCard[];
  /** 지금 손패의 상태 버전 — 이보다 낮은 손패(낡은 resync·늦게 온 이벤트)는 버린다. */
  handVersion: number;

  // ── UI 로컬 ──
  /** 고른 카드의 `cardKey`. 54장이 모두 달라 값으로 고른다 — 손패를 정렬해 보여 줘도 어긋나지 않는다. */
  selectedKey: string | null;
  /** 7 을 낼 때 지정할 무늬. */
  suitChoice: OneCardSuit | null;
  press: PendingPress | null;
  /** BUSY 재시도 신호 — 게임판이 이 값의 변화를 보고 같은 누름을 다시 보낸다. */
  retryNonce: number;
  /** `NO_RACE` 로 거절된 누름 — 오류 대신 "늦었어요"를 잠깐 보여 준다. */
  raceNotice: 'LATE' | null;

  // ── 메타 ──
  disconnectedSeats: Set<number>;
  errorMessage: string | null;
  turnStartedAt: number;
}

export interface OneCardActions {
  reset: (roomId: string) => void;
  applySnapshot: (snapshot: ResyncEnvelope<OneCardTableView, OneCardPrivateView>) => void;
  applyPrivateHand: (payload: HandPayload) => void;
  applyEvent: (envelope: { type: string; seq?: number; payload: unknown }) => ApplyEventResult;
  setError: (message: string | null) => void;
  selectCard: (key: string | null) => void;
  setSuitChoice: (suit: OneCardSuit | null) => void;
  startPress: (raceId: number, action: PressAction) => void;
  /**
   * 내 누름이 거절됐다. 누름을 기다리던 중이면 처리하고 true — `BUSY` 는 창이 열려 있고 횟수가 남았으면 재시도
   * 신호를 올리고, `NO_RACE` 는 "늦었어요"로 바꾼다. 기다리던 누름이 없거나 재시도할 수 없으면 false(일반 오류로).
   */
  notePressRejected: (code: 'BUSY' | 'NO_RACE') => boolean;
  clearRaceNotice: () => void;
}

const INITIAL: OneCardRoomState = {
  roomId: null,
  phase: null,
  seats: [],
  topCard: null,
  declaredSuit: null,
  attackStack: 0,
  direction: 1,
  turnSeat: -1,
  drawPileCount: 0,
  race: null,
  lastRace: null,
  result: null,
  mySeat: -1,
  hand: [],
  handVersion: 0,
  selectedKey: null,
  suitChoice: null,
  press: null,
  retryNonce: 0,
  raceNotice: null,
  disconnectedSeats: new Set(),
  errorMessage: null,
  turnStartedAt: 0,
};

/** 좌석 하나만 갱신한 새 배열. */
function patchSeat(
  seats: OneCardSeatView[],
  seat: number,
  patch: Partial<OneCardSeatView>,
): OneCardSeatView[] {
  return seats.map((s) => (s.seat === seat ? { ...s, ...patch } : s));
}

function toClientRace(
  race: Omit<OneCardClientRace, 'closesAt'>,
  remainingMillis: number,
): OneCardClientRace {
  return {
    raceId: race.raceId,
    ownerSeat: race.ownerSeat,
    slot: race.slot,
    jitterX: race.jitterX,
    jitterY: race.jitterY,
    windowMillis: race.windowMillis,
    closesAt: Date.now() + remainingMillis,
  };
}

/** 새 손패에 고른 카드가 남아 있으면 선택을 유지한다(벌칙 먹기로 손패가 바뀌어도 고른 카드는 그대로). */
function keepSelection(
  hand: OneCardCard[],
  selectedKey: string | null,
): Pick<OneCardRoomState, 'selectedKey' | 'suitChoice'> | Record<string, never> {
  if (selectedKey !== null && hand.some((c) => cardKey(c) === selectedKey)) return {};
  return { selectedKey: null, suitChoice: null };
}

/**
 * 원카드 방 상태 — 순수 리듀서 (D-124: 순번 판정은 `useStompRoom` 이 sink 앞에서 끝낸다).
 *
 * <p>공개 payload 가 증감이 아니라 **결과값**이라(D-128) 같은 이벤트를 두 번 적용해도 같다. 손패는 비공개
 * 이벤트·resync 가 **전체**를 싣고 `handVersion` 이 단조 증가하므로, 가진 것보다 낮은 버전은 버린다 — 순번이 없는
 * 비공개 이벤트(D-129)와 잠금 밖 resync 의 도착 순서가 뒤바뀌어도 손패가 되돌아가지 않는다.
 */
export const useOneCardStore = create<OneCardRoomState & OneCardActions>((set, get) => ({
  ...INITIAL,

  reset(roomId) {
    set({ ...INITIAL, roomId, disconnectedSeats: new Set() });
  },

  applySnapshot(snap) {
    const t = snap.tableView;
    const state = get();
    const priv = snap.privateHand;
    const race = t.race ? toClientRace(t.race, t.race.remainingMillis) : null;

    // 관전자는 privateHand 가 null 이다(서버 계약). 낡은 손패(더 낮은 버전)면 지금 손패를 지킨다.
    let mine: Partial<OneCardRoomState>;
    if (priv === null) {
      mine = { mySeat: -1, hand: [], handVersion: 0, selectedKey: null, suitChoice: null };
    } else if (priv.handVersion >= state.handVersion) {
      mine = {
        mySeat: priv.seat,
        hand: priv.hand,
        handVersion: priv.handVersion,
        ...keepSelection(priv.hand, state.selectedKey),
      };
    } else {
      mine = { mySeat: priv.seat };
    }

    set({
      phase: t.phase,
      seats: t.seats,
      topCard: t.topCard,
      declaredSuit: t.declaredSuit,
      attackStack: t.attackStack,
      direction: t.direction,
      turnSeat: t.turnSeat,
      drawPileCount: t.drawPileCount,
      race,
      result: t.result,
      ...mine,
      // 같은 창을 기다리던 누름만 남긴다.
      press: race && state.press?.raceId === race.raceId ? state.press : null,
      disconnectedSeats: new Set(snap.disconnectedSeats ?? []),
      errorMessage: null,
      turnStartedAt: Date.now(),
    });
  },

  applyPrivateHand(payload) {
    const state = get();
    if (payload.handVersion < state.handVersion) return;
    set({
      mySeat: payload.seat,
      hand: payload.hand,
      handVersion: payload.handVersion,
      ...keepSelection(payload.hand, state.selectedKey),
    });
  },

  applyEvent(envelope) {
    const { type, payload } = envelope;
    const state = get();

    // D-122 와 같은 심층 방어 — 매치가 끝났으면 진행 이벤트는 반영하지 않는다(종료 화면을 유지하는 동안
    // 잔여 이벤트가 판을 움직이지 않게). 연결 상태 배지는 그대로 반영한다.
    if (state.result && type !== 'PLAYER_DISCONNECTED' && type !== 'PLAYER_RECONNECTED') {
      return 'ignored';
    }

    switch (type) {
      case 'PLAYER_DISCONNECTED':
      case 'PLAYER_RECONNECTED': {
        const { seat } = payload as { seat: number };
        const next = new Set(state.disconnectedSeats);
        if (type === 'PLAYER_DISCONNECTED') next.add(seat);
        else next.delete(seat);
        set({ disconnectedSeats: next });
        return 'applied';
      }

      case 'CARD_PLAYED': {
        const p = payload as CardPlayedPayload;
        set({
          topCard: p.card,
          declaredSuit: p.declaredSuit ?? null,
          attackStack: p.attackStack,
          direction: p.direction,
          seats: patchSeat(state.seats, p.seat, { handCount: p.handCount }),
          // 다음 차례는 TURN_CHANGED(또는 경쟁 창)가 정한다 — 그 사이 '내 차례'를 잘못 세우지 않는다.
          turnSeat: -1,
          lastRace: null,
          ...(p.seat === state.mySeat ? { selectedKey: null, suitChoice: null } : {}),
        });
        return 'applied';
      }

      case 'CARDS_DRAWN': {
        const p = payload as CardsDrawnPayload;
        set({
          seats: patchSeat(state.seats, p.seat, { handCount: p.handCount }),
          drawPileCount: p.drawPileCount,
        });
        return 'applied';
      }

      case 'PILE_RESHUFFLED': {
        const p = payload as PileReshuffledPayload;
        set({ drawPileCount: p.drawPileCount });
        return 'applied';
      }

      case 'TURN_CHANGED': {
        const p = payload as TurnChangedPayload;
        set({
          phase: 'PLAYING',
          turnSeat: p.seat,
          direction: p.direction,
          attackStack: p.attackStack,
          turnStartedAt: Date.now(),
          // 매 플레이 resync 가 지워 주던 거절 문구를 차례가 바뀔 때 지운다(D-126 의 티츄와 같은 처리).
          errorMessage: null,
        });
        return 'applied';
      }

      case 'RACE_OPENED': {
        const p = payload as RaceOpenedPayload;
        set({
          phase: 'RACE',
          race: toClientRace(p, p.windowMillis),
          turnSeat: -1,
          lastRace: null,
          press: null,
          raceNotice: null,
        });
        return 'applied';
      }

      case 'RACE_RESOLVED': {
        const p = payload as RaceResolvedPayload;
        // 지금 창의 해소만 창을 닫는다(다른 창 번호면 다른 창이 열려 있는 것이다).
        const current = state.race?.raceId === p.raceId;
        set({
          phase: current || state.race === null ? 'PLAYING' : state.phase,
          race: current ? null : state.race,
          lastRace: {
            raceId: p.raceId,
            ownerSeat: current ? state.race!.ownerSeat : -1,
            outcome: p.outcome,
            bySeat: p.bySeat,
          },
          press: state.press?.raceId === p.raceId ? null : state.press,
        });
        return 'applied';
      }

      case 'PLAYER_ELIMINATED': {
        const p = payload as PlayerEliminatedPayload;
        set({
          // 탈락자 손패는 뽑을 더미 맨 아래로 간다(§10) — 장수는 0, 더미 장수는 최종값.
          seats: patchSeat(state.seats, p.seat, { eliminated: p.reason, handCount: 0 }),
          drawPileCount: p.drawPileCount,
        });
        return 'applied';
      }

      case 'MATCH_ENDED': {
        const p = payload as MatchEndedPayload;
        set({ phase: 'ENDED', result: p, turnSeat: -1, race: null, press: null });
        return 'applied';
      }

      default:
        // MATCH_STARTED(서버는 보내지 않는다 — 시작 상태는 resync 로 받는다) 등 — 훅이 resync 로 자기치유한다.
        return 'unhandled';
    }
  },

  setError(message) {
    set({ errorMessage: message });
  },

  selectCard(key) {
    set({ selectedKey: key, suitChoice: null });
  },

  setSuitChoice(suit) {
    set({ suitChoice: suit });
  },

  startPress(raceId, action) {
    set({ press: { raceId, action, attempts: 1 }, raceNotice: null });
  },

  notePressRejected(code) {
    const { press, race, retryNonce } = get();
    if (!press) return false;
    if (code === 'NO_RACE') {
      set({ press: null, raceNotice: 'LATE' });
      return true;
    }
    const open = race !== null && race.raceId === press.raceId && Date.now() < race.closesAt;
    if (open && press.attempts < MAX_PRESS_ATTEMPTS) {
      set({ press: { ...press, attempts: press.attempts + 1 }, retryNonce: retryNonce + 1 });
      return true;
    }
    set({ press: null });
    return false;
  },

  clearRaceNotice() {
    set({ raceNotice: null });
  },
}));
```

- [ ] **Step 4: 구현 — 수신부**

`client/src/features/onecard/onecardRoomSink.ts`:

```ts
import type { RoomEventSink } from '@/ws/roomEventSink';
import type { StompEnvelope } from '@/types/stomp';
import type { HandPayload, OneCardPrivateView, OneCardTableView } from '@/types/onecard';
import { useOneCardStore } from './onecardStore';

interface ErrorPayload {
  code: string;
  message: string;
}

/** 서버 거절 사유(원카드 `RejectionReason` + 인프라 공통) → 사용자 문구. */
const ERROR_LABEL: Record<string, string> = {
  MATCH_OVER: '이미 끝난 판입니다.',
  PLAYER_ELIMINATED: '탈락한 좌석은 더 할 수 없습니다.',
  RACE_IN_PROGRESS: '원카드 경쟁 중에는 내거나 먹을 수 없습니다.',
  NOT_YOUR_TURN: '아직 당신의 차례가 아닙니다.',
  CARD_NOT_OWNED: '손패에 없는 카드입니다.',
  INVALID_SUIT_DECLARATION: '7 을 낼 때는 무늬를 하나 골라야 합니다.',
  CARD_NOT_PLAYABLE: '지금 낼 수 없는 카드입니다.',
  COUNTER_REQUIRED: '공격받는 중에는 반격 카드만 낼 수 있습니다. 반격할 수 없으면 먹으세요.',
  NO_RACE: '이미 끝난 경쟁입니다.',
  NOT_RACE_OWNER: '"원카드!"는 카드가 1장 남은 사람만 누를 수 있습니다.',
  OWNER_CANNOT_CATCH: '자기 자신은 잡을 수 없습니다.',
  BUSY: '다른 처리가 진행 중입니다. 잠시 후 다시 시도하세요.',
  GAME_NOT_STARTED: '아직 게임이 시작되지 않았습니다.',
  GAME_NOT_IN_PROGRESS: '이미 끝난 게임입니다.',
};

/**
 * 원카드용 {@link RoomEventSink}. 규약대로 모듈 상수이고, 각 메서드는 호출 시점에 `useOneCardStore.getState()` 를
 * 읽는다(D-103).
 *
 * <p>비공개 큐에는 손패 이벤트 두 종류와 `ERROR` 가 온다. 경쟁 누름의 거절은 일반 오류와 다르게 다룬다 — 락 경합
 * `BUSY` 는 창이 열린 동안 다시 보내고, 이미 닫힌 창의 `NO_RACE` 는 오류 대신 "늦었어요"로 보여 준다(설계서 §4.4).
 */
export const onecardRoomSink: RoomEventSink<OneCardTableView, OneCardPrivateView> = {
  reset(roomId) {
    useOneCardStore.getState().reset(roomId);
  },

  applySnapshot(snap) {
    useOneCardStore.getState().applySnapshot(snap);
  },

  applyEvent(envelope) {
    return useOneCardStore.getState().applyEvent(envelope);
  },

  applyPrivateEvent(envelope: StompEnvelope<unknown>) {
    const store = useOneCardStore.getState();
    if (envelope.type === 'HAND_DEALT' || envelope.type === 'HAND_UPDATED') {
      store.applyPrivateHand(envelope.payload as HandPayload);
    } else if (envelope.type === 'ERROR') {
      const p = envelope.payload as ErrorPayload;
      if ((p.code === 'BUSY' || p.code === 'NO_RACE') && store.notePressRejected(p.code)) return;
      store.setError(ERROR_LABEL[p.code] ?? `${p.code}: ${p.message}`);
    }
    // 그 외 타입은 조용히 무시한다.
  },

  setError(message) {
    useOneCardStore.getState().setError(message);
  },
};

export { ERROR_LABEL as onecardErrorLabels };
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix client run test -- onecardStore onecardRoomSink`
Expected: PASS — 37건(스토어 31 · 수신부 6).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  51 passed (51)` · `Tests  483 passed (483)`.

- [ ] **Step 6: 커밋**

```bash
git add client/src/features/onecard/onecardStore.ts \
  client/src/features/onecard/onecardStore.test.ts \
  client/src/features/onecard/onecardRoomSink.ts \
  client/src/features/onecard/onecardRoomSink.test.ts
git commit -m "feat(D-129): 원카드 스토어(순수 리듀서·handVersion·누름 거절)와 이벤트 수신부

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: 클라 — 카드 칩·경쟁 슬롯·튜토리얼, 레지스트리와 위키 링크

**Files:**
- Create: `client/src/features/onecard/OneCardCardChip.tsx`, `client/src/features/onecard/raceSlots.ts`, `client/src/features/onecard/raceSlots.test.ts`, `client/src/features/onecard/tutorial/onecardTutorial.ts`,
  `client/src/features/onecard/tutorial/onecardTutorialSteps.tsx`, `client/src/features/onecard/tutorial/onecardTutorialSteps.test.tsx`, `client/src/features/onecard/tutorial/PlayQuiz.tsx`, `client/src/features/onecard/tutorial/PlayQuiz.test.tsx`,
  `client/src/features/onecard/tutorial/ReactionPractice.tsx`, `client/src/features/onecard/tutorial/ReactionPractice.test.tsx`, `client/src/features/lobby/gameWiki.test.ts`
- Modify: `client/src/features/tutorial/gameTutorials.ts`, `client/src/features/tutorial/gameTutorials.test.ts`, `client/src/features/lobby/gameWiki.ts`

**Interfaces:**
- Consumes: Task 2 `cardLabel`·`rankLabel`·`SUIT_SYMBOL`·`SUIT_LABEL`·`canPlay`, `features/tutorial/types.ts` 의 `GameTutorial`·`TutorialStep`,
  `components/ui/button`.
- Produces: `OneCardCardChip({card, selected?, dimmed?, onClick?, compact?})` — 버튼이면 `aria-label`=`cardLabel`·`aria-pressed`, 아니면
  `role="img"`. `RACE_SLOT_COUNT`(8)·`RACE_SLOTS`·`RACE_JITTER_RADIUS`·`racePosition(slot, jitterX, jitterY) → {left, top}`(뷰포트 %).
  `ONE_CARD_TUTORIAL`(`seenKey` `mirboard.tutorial.one_card.seen.v1`, `bodyClassName` `oc-tokens`)·`ONE_CARD_TUTORIAL_STEPS`(12단계)·
  `PlayQuestion`·`PLAY_QUESTIONS`·`PlayQuiz`·`ReactionPractice({random?})`·`PRACTICE_*_MS`. 레지스트리 `GAME_TUTORIALS.ONE_CARD`, 위키 `one_card`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/raceSlots.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { RACE_JITTER_RADIUS, RACE_SLOT_COUNT, RACE_SLOTS, racePosition } from './raceSlots';

/**
 * 경쟁 버튼 위치는 프로토콜 상수다 — 슬롯 수가 서버(`RaceSettings` 슬롯 8)와 같아야 하고, 어떤 지터에서도 버튼 중심이
 * 손패 영역(아래 약 40%)과 화면 가장자리를 피해야 한다.
 */
describe('경쟁 버튼 슬롯', () => {
  it('슬롯은 8개이고 서로 다른 자리다', () => {
    expect(RACE_SLOT_COUNT).toBe(8);
    expect(RACE_SLOTS).toHaveLength(RACE_SLOT_COUNT);
    expect(new Set(RACE_SLOTS.map((s) => `${s.x},${s.y}`)).size).toBe(RACE_SLOT_COUNT);
  });

  it('지터가 0 이면 슬롯 중심이다', () => {
    RACE_SLOTS.forEach((s, slot) => {
      expect(racePosition(slot, 0, 0)).toEqual({ left: s.x, top: s.y });
    });
  });

  it('지터는 슬롯 반경의 백분율로 움직인다', () => {
    const s = RACE_SLOTS[1];
    expect(racePosition(1, 100, -50)).toEqual({
      left: s.x + RACE_JITTER_RADIUS.x,
      top: s.y - RACE_JITTER_RADIUS.y / 2,
    });
  });

  it('어떤 슬롯·지터에서도 화면 가장자리와 손패 영역을 피한다', () => {
    for (let slot = 0; slot < RACE_SLOT_COUNT; slot++) {
      for (const jx of [-100, 0, 100]) {
        for (const jy of [-100, 0, 100]) {
          const { left, top } = racePosition(slot, jx, jy);
          expect(left, `slot ${slot} x`).toBeGreaterThanOrEqual(5);
          expect(left, `slot ${slot} x`).toBeLessThanOrEqual(95);
          expect(top, `slot ${slot} y`).toBeGreaterThanOrEqual(5);
          expect(top, `slot ${slot} y`).toBeLessThanOrEqual(60);
        }
      }
    }
  });

  it('범위 밖 슬롯·지터는 잘라 쓴다', () => {
    expect(racePosition(8, 0, 0)).toEqual(racePosition(0, 0, 0));
    expect(racePosition(-1, 0, 0)).toEqual(racePosition(7, 0, 0));
    expect(racePosition(0, 500, -500)).toEqual(racePosition(0, 100, -100));
  });
});
```

`client/src/features/onecard/tutorial/onecardTutorialSteps.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ONE_CARD_TUTORIAL_STEPS } from './onecardTutorialSteps';
import { ONE_CARD_TUTORIAL } from './onecardTutorial';

/**
 * D-129 — 튜토리얼 문구 ↔ `docs/rules-onecard.md` 동기화 가드. 튜토리얼은 룰 문서의 요약이라 룰이 바뀌면 조용히
 * 틀린 설명을 한다. 단계마다 근거 §를 `source` 로 박고, 해석이 갈리는 수치·예외 문구를 여기서 고정한다.
 */

const steps = ONE_CARD_TUTORIAL_STEPS;

function bodyText(title: string): string {
  const step = steps.find((s) => s.title === title);
  if (!step) throw new Error(`단계 없음: ${title}`);
  const { container, unmount } = render(<>{step.body}</>);
  const text = container.textContent ?? '';
  unmount();
  return text;
}

describe('원카드 튜토리얼 — 구조', () => {
  it('12단계이고 제목이 고유하다', () => {
    expect(steps).toHaveLength(12);
    expect(new Set(steps.map((s) => s.title)).size).toBe(12);
  });

  it('첫 단계는 환영, 끝의 두 단계는 퀴즈와 반응 연습이다', () => {
    expect(steps[0].title).toBe('미르보드 원카드에 오신 걸 환영합니다');
    expect(steps[10].title).toBe('연습 — 낼 수 있을까?');
    expect(steps[11].title).toBe('연습 — 원카드! 반응');
  });

  it('모든 단계가 룰 문서 §를 근거로 인용한다', () => {
    for (const s of steps) {
      expect(s.source, `${s.title} 에 source 가 없다`).toMatch(/§\d+/);
    }
  });

  it('선언이 단계·전용 열람 키·토큰 클래스를 갖는다', () => {
    expect(ONE_CARD_TUTORIAL.steps).toBe(steps);
    expect(ONE_CARD_TUTORIAL.seenKey).toBe('mirboard.tutorial.one_card.seen.v1');
    // 다이얼로그는 body 포털(.oc-table 밖)이라 칩·연습 버튼 토큰을 여기서 푼다.
    expect(ONE_CARD_TUTORIAL.bodyClassName).toBe('oc-tokens');
  });
});

describe('원카드 튜토리얼 — 룰 문서와 맞물린 문구 (§ 인용)', () => {
  it('인원 2~6명 (§2)', () => {
    expect(bodyText('미르보드 원카드에 오신 걸 환영합니다')).toContain('2~6명');
  });

  it('카드 54장과 공격 값 +2·+3·+5·+7 (§1)', () => {
    const text = bodyText('카드 구성 — 54장');
    expect(text).toContain('54장');
    for (const v of ['2(+2)', 'A(+3)', '흑백 조커(+5)', '컬러 조커(+7)']) expect(text).toContain(v);
  });

  it('7장씩 나누고 시작 카드는 일반 카드 (§3)', () => {
    const text = bodyText('분배와 시작');
    expect(text).toContain('7장');
    expect(text).toContain('일반 카드');
  });

  it('낼 수 있어도 먹을 수 있고, 먹으면 차례가 끝난다 (§4·§7)', () => {
    const text = bodyText('내 차례 — 내기 또는 먹기');
    expect(text).toContain('일부러 먹을 수 있습니다');
    expect(text).toContain('차례가 끝납니다');
  });

  it('7 은 와일드가 아니다 (§5.2)', () => {
    expect(bodyText('낼 수 있는 카드')).toContain('7 은 와일드가 아닙니다');
  });

  it('반격 세기 순서 (§6.2)', () => {
    expect(bodyText('공격과 반격')).toContain('2 < A < 흑백 조커 < 컬러 조커');
  });

  it('2인이면 J·Q 도 한 번 더 (§8.1)', () => {
    expect(bodyText('특수 카드 — J·Q·K·7')).toContain('2명이면 J·Q 도 "한 번 더"');
  });

  it('경쟁 창 3초·벌칙 1장·봇 1.0~2.5초 (§9)', () => {
    const text = bodyText('원카드! 잡기!');
    expect(text).toContain('3초');
    expect(text).toContain('벌칙 1장');
    expect(text).toContain('1.0~2.5초');
  });

  it('파산은 20장 이상, 탈락 뒤 나가기는 탈주가 아니다 (§10)', () => {
    const text = bodyText('파산과 탈락');
    expect(text).toContain('20장 이상');
    expect(text).toContain('이미 탈락한 뒤에 나가는 것은 탈주가 아닙니다');
  });

  it('동순위는 1, 1, 3 (§11.2)', () => {
    expect(bodyText('종료와 순위')).toContain('1, 1, 3');
  });
});
```

`client/src/features/onecard/tutorial/PlayQuiz.test.tsx`:

```tsx
/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다.
import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PLAY_QUESTIONS, PlayQuiz } from './PlayQuiz';
import { canPlay } from '../onecardRules';

/**
 * D-129 — '낼 수 있을까?' 연습. 정답은 정적 데이터지만, 문제마다 클라 규칙 미러(`canPlay`)와 대조해 룰이 바뀌면
 * 여기서 먼저 빨개진다.
 */

function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const status = () => screen.getByRole('status');

describe('PlayQuiz', () => {
  it('정답 데이터가 클라 규칙 미러와 일치한다', () => {
    for (const q of PLAY_QUESTIONS) {
      expect(canPlay(q.candidate, q.top, q.declaredSuit, q.attackStack), q.explain).toBe(q.answer);
    }
  });

  it('낼 수 있는 경우와 없는 경우를 모두 묻는다', () => {
    expect(PLAY_QUESTIONS.some((q) => q.answer)).toBe(true);
    expect(PLAY_QUESTIONS.some((q) => !q.answer)).toBe(true);
    expect(PLAY_QUESTIONS.some((q) => q.attackStack > 0)).toBe(true);
  });

  it('맞히면 정답과 해설, 틀리면 바른 답과 해설을 보여 준다', () => {
    render(<PlayQuiz />);
    expect(status()).toHaveTextContent('스페이드 9, 지금 낼 수 있을까요?');

    fireEvent.click(screen.getByRole('button', { name: '낼 수 있다' }));
    expect(status()).toHaveTextContent('정답! 숫자가 같으면');

    fireEvent.click(screen.getByRole('button', { name: '낼 수 없다' }));
    expect(status()).toHaveTextContent('아니에요 — 낼 수 있습니다.');
  });

  it('다음 문제로 넘어가며 마지막 다음은 처음이다', () => {
    render(<PlayQuiz />);
    const next = () => fireEvent.click(screen.getByRole('button', { name: '다음 문제' }));

    next();
    expect(screen.getByText(`문제 2 / ${PLAY_QUESTIONS.length}`)).toBeInTheDocument();
    for (let i = 1; i < PLAY_QUESTIONS.length; i++) next();
    expect(screen.getByText(`문제 1 / ${PLAY_QUESTIONS.length}`)).toBeInTheDocument();
  });

  it('소켓·스토어를 쓰지 않는다 — 로컬 연습이다', () => {
    const source = readSource('./PlayQuiz.tsx');
    expect(source).not.toMatch(/useStompRoom|onecardStore|onecardRoomSink/);
  });
});
```

`client/src/features/onecard/tutorial/ReactionPractice.test.tsx`:

```tsx
/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다.
import { readFileSync } from 'node:fs';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PRACTICE_DELAY_SPAN_MS,
  PRACTICE_MIN_DELAY_MS,
  PRACTICE_WINDOW_MS,
  ReactionPractice,
} from './ReactionPractice';

/** D-129 — '원카드!' 반응 연습. 무작위 대기 뒤 슬롯 표의 무작위 자리에 버튼이 뜨고, 누르기까지의 시간을 보여 준다. */

function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const status = () => screen.getByRole('status');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  vi.setSystemTime(0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ReactionPractice', () => {
  it('시작하면 대기 뒤 버튼이 뜨고, 누르면 반응 시간을 보여 준다', () => {
    render(<ReactionPractice random={() => 0.5} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    expect(status()).toHaveTextContent('곧 나타납니다');
    expect(screen.queryByRole('button', { name: '원카드!' })).toBeNull();

    act(() => {
      vi.advanceTimersByTime(PRACTICE_MIN_DELAY_MS + PRACTICE_DELAY_SPAN_MS / 2);
    });
    const button = screen.getByRole('button', { name: '원카드!' });
    expect(document.activeElement).not.toBe(button);

    act(() => {
      vi.advanceTimersByTime(432);
    });
    fireEvent.click(button);

    expect(status()).toHaveTextContent('반응 시간 432ms');
    expect(screen.getByRole('button', { name: '다시' })).toBeEnabled();
  });

  it('3초 안에 안 누르면 실제 창처럼 닫힌다', () => {
    render(<ReactionPractice random={() => 0} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    act(() => {
      vi.advanceTimersByTime(PRACTICE_MIN_DELAY_MS);
    });
    expect(screen.getByRole('button', { name: '원카드!' })).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(PRACTICE_WINDOW_MS);
    });
    expect(screen.queryByRole('button', { name: '원카드!' })).toBeNull();
    expect(status()).toHaveTextContent('3초가 지났습니다');
  });

  it('대기·표시 중에는 다시 시작할 수 없다', () => {
    render(<ReactionPractice random={() => 0} />);

    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    expect(screen.getByRole('button', { name: '다시' })).toBeDisabled();
  });

  it('소켓·스토어를 쓰지 않는다 — 로컬 연습이다', () => {
    const source = readSource('./ReactionPractice.tsx');
    expect(source).not.toMatch(/useStompRoom|onecardStore|onecardRoomSink/);
  });
});
```

`client/src/features/lobby/gameWiki.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { gameWikiUrl } from './gameWiki';

/** 게임 id → 외부 규칙 문서. 허브의 "자세히" 링크가 쓴다(D-74). 대소문자는 소문자로 정규화한다. */
describe('gameWikiUrl', () => {
  it('원카드는 나무위키 원카드 문서다', () => {
    expect(gameWikiUrl('ONE_CARD')).toBe('https://namu.wiki/w/%EC%9B%90%EC%B9%B4%EB%93%9C');
    expect(decodeURIComponent(gameWikiUrl('one_card')!)).toBe('https://namu.wiki/w/원카드');
  });

  it('등록된 다른 게임도 그대로다', () => {
    expect(gameWikiUrl('TICHU')).toBe('https://en.wikipedia.org/wiki/Tichu');
    expect(gameWikiUrl('SKULL_KING')).toContain('boardgamegeek.com');
  });

  it('없는 게임은 undefined — "자세히" 링크를 그리지 않는다', () => {
    expect(gameWikiUrl('YACHT')).toBeUndefined();
  });
});
```

`client/src/features/tutorial/gameTutorials.test.ts`:

```diff
 import { GAME_TUTORIALS, tutorialFor } from './gameTutorials';
 import { TICHU_TUTORIAL } from '@/features/tichu/tutorial/tichuTutorial';
 import { SKULL_KING_TUTORIAL } from '@/features/skullking/tutorial/skullkingTutorial';
+import { ONE_CARD_TUTORIAL } from '@/features/onecard/tutorial/onecardTutorial';
 
 /**
  * D-121 — 튜토리얼 레지스트리. 클라에서 "튜토리얼용 게임 id" 를 아는 곳은 여기 하나뿐이다.
```

```diff
 
   it('스컬킹 id 로 스컬킹 튜토리얼을 돌려준다', () => {
     expect(tutorialFor('SKULL_KING')).toBe(SKULL_KING_TUTORIAL);
+  });
+
+  it('원카드 id 로 원카드 튜토리얼을 돌려준다', () => {
+    expect(tutorialFor('ONE_CARD')).toBe(ONE_CARD_TUTORIAL);
+    expect(tutorialFor('one_card')).toBe(ONE_CARD_TUTORIAL);
   });
 
   it('대소문자를 정규화한다 (loadGame 과 같은 규약)', () => {
```

```diff
 describe('게임 중립 페이지는 게임 튜토리얼 폴더를 직접 import 하지 않는다', () => {
   it.each(['../../pages/GameHubPage.tsx', '../../pages/RoomPage.tsx'])('%s', (path) => {
     const source = readSource(path);
-    expect(source).not.toMatch(/features\/(tichu|skullking)\/tutorial/);
+    expect(source).not.toMatch(/features\/(tichu|skullking|onecard)\/tutorial/);
   });
 });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- raceSlots onecard/tutorial gameTutorials gameWiki`
Expected: FAIL — `Test Files  6 failed (6)` · `Tests  1 failed | 2 passed (3)`. 다섯 파일은 import 를 못 찾고(`Failed to resolve import`
— `"./raceSlots"`·`"./onecardTutorialSteps"`·`"./PlayQuiz"`·`"./ReactionPractice"`·`"@/features/onecard/tutorial/onecardTutorial"`),
`gameWiki.test` 는 원카드 링크가 없어 `원카드는 나무위키 원카드 문서다` 1건이 실패한다.

- [ ] **Step 3: 구현 — 카드 칩과 경쟁 슬롯**

`client/src/features/onecard/OneCardCardChip.tsx`:

```tsx
import { SUIT_SYMBOL, cardLabel, rankLabel, type OneCardCard } from '@/types/onecard';

interface Props {
  card: OneCardCard;
  selected?: boolean;
  /** 지금 낼 수 없는 카드 흐리게 (표시 전용 — 판정은 서버). */
  dimmed?: boolean;
  onClick?: () => void;
  /** 튜토리얼·작은 자리용. */
  compact?: boolean;
}

/** 무늬 색 — ♥♦ 빨강, ♠♣ 검정, 조커는 따로. */
function toneOf(card: OneCardCard): string {
  if (card.joker === 'COLOR') return 'oc-card-joker-color';
  if (card.joker === 'BLACK') return 'oc-card-joker-black';
  return card.suit === 'HEART' || card.suit === 'DIAMOND' ? 'oc-card-red' : 'oc-card-black';
}

/**
 * 원카드 카드 한 장 — 이미지 없이 무늬 기호와 숫자로 그린다(D-129). `.card-chip` 공용 CSS 로 크기·모서리·그림자를
 * 받고, 색과 배치만 `.oc-card*` 가 정한다.
 */
export function OneCardCardChip({
  card,
  selected = false,
  dimmed = false,
  onClick,
  compact = false,
}: Props) {
  const className = [
    'card-chip',
    'oc-card',
    toneOf(card),
    compact ? 'oc-card-compact' : '',
    selected ? 'oc-card-selected' : '',
    dimmed ? 'oc-card-dimmed' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const label = cardLabel(card);

  const body = card.joker ? (
    <>
      <span className="oc-card-joker-mark" aria-hidden>
        ★
      </span>
      <span className="oc-card-name" aria-hidden>
        {card.joker === 'COLOR' ? '컬러' : '흑백'}
      </span>
      <span className="oc-card-name" aria-hidden>
        조커
      </span>
    </>
  ) : (
    <>
      <span className="oc-card-rank" aria-hidden>
        {rankLabel(card.rank)}
      </span>
      <span className="oc-card-suit" aria-hidden>
        {SUIT_SYMBOL[card.suit!]}
      </span>
    </>
  );

  if (!onClick) {
    return (
      <span className={className} role="img" aria-label={label}>
        {body}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={className}
      aria-label={label}
      aria-pressed={selected}
      onClick={onClick}
    >
      {body}
    </button>
  );
}
```

`client/src/features/onecard/raceSlots.ts`:

```ts
/**
 * 경쟁 버튼 슬롯 — **프로토콜 상수**다. 서버는 창마다 `slot`(0..7)과 `jitterX`·`jitterY`(−100..100, 슬롯 반경의
 * 백분율)를 골라 전원에게 같은 값을 보낸다(`docs/stomp-protocol.md` 원카드 절). 전원이 같은 위치를 받으므로 위치 운은
 * 공평하다(설계서 §4.4).
 *
 * <p>좌표는 **화면(뷰포트) 기준 %**다. 게임판은 세로로 길어 모바일에서 스크롤되므로 게임판 기준으로 두면 버튼이 화면
 * 밖에 뜰 수 있다. 손패와 버튼 줄이 놓이는 아래쪽(대략 60% 아래)과 화면 가장자리를 피한다. 이 표를 바꾸면 모든
 * 클라의 위치가 함께 바뀌므로 서버와 맞출 것은 없지만, 개수(8)는 서버 `RaceSettings` 와 같아야 한다.
 */
export const RACE_SLOT_COUNT = 8;

export const RACE_SLOTS: ReadonlyArray<{ readonly x: number; readonly y: number }> = [
  { x: 22, y: 18 },
  { x: 50, y: 14 },
  { x: 78, y: 18 },
  { x: 16, y: 38 },
  { x: 84, y: 38 },
  { x: 36, y: 52 },
  { x: 64, y: 52 },
  { x: 50, y: 30 },
];

/** 지터가 버튼을 옮기는 최대 거리 — 뷰포트 % (가로, 세로). */
export const RACE_JITTER_RADIUS = { x: 10, y: 6 } as const;

function clampJitter(v: number): number {
  return Math.max(-100, Math.min(100, v));
}

/** 슬롯 + 지터 → 버튼 중심의 뷰포트 % 좌표. 범위 밖 값은 잘라 쓴다(계약 밖 방어). */
export function racePosition(
  slot: number,
  jitterX: number,
  jitterY: number,
): { left: number; top: number } {
  const base = RACE_SLOTS[((slot % RACE_SLOT_COUNT) + RACE_SLOT_COUNT) % RACE_SLOT_COUNT];
  return {
    left: base.x + (clampJitter(jitterX) / 100) * RACE_JITTER_RADIUS.x,
    top: base.y + (clampJitter(jitterY) / 100) * RACE_JITTER_RADIUS.y,
  };
}
```

- [ ] **Step 4: 구현 — 튜토리얼**

`client/src/features/onecard/tutorial/PlayQuiz.tsx`:

```tsx
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { SUIT_LABEL, SUIT_SYMBOL, cardLabel, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import { OneCardCardChip } from '../OneCardCardChip';

/**
 * D-129 — '낼 수 있을까?' 연습. 정답은 **정적 데이터**다(클라가 판정하지 않는다). `PlayQuiz.test` 가 문제마다 정답을
 * 클라 규칙 미러(`onecardRules.canPlay`)와 대조하므로 룰을 바꾸면 거기서 먼저 빨개진다. 근거는 문제별 주석의
 * `docs/rules-onecard.md` §다.
 */

export interface PlayQuestion {
  top: OneCardCard;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  candidate: OneCardCard;
  answer: boolean;
  explain: string;
}

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const BLACK_JOKER: OneCardCard = { suit: null, rank: 0, joker: 'BLACK' };

export const PLAY_QUESTIONS: PlayQuestion[] = [
  // §5.2 — 같은 숫자.
  {
    top: c('HEART', 9),
    declaredSuit: null,
    attackStack: 0,
    candidate: c('SPADE', 9),
    answer: true,
    explain: '숫자가 같으면 무늬가 달라도 낼 수 있습니다.',
  },
  // §5.2·§12-17 — 7 은 와일드가 아니다.
  {
    top: c('HEART', 9),
    declaredSuit: null,
    attackStack: 0,
    candidate: c('CLUB', 7),
    answer: false,
    explain: '7 은 무늬를 지정할 뿐 아무 데나 내는 카드가 아닙니다. 무늬나 숫자가 맞아야 합니다.',
  },
  // §5.1 — 7 로 지정된 무늬가 기준이다.
  {
    top: c('HEART', 7),
    declaredSuit: 'CLUB',
    attackStack: 0,
    candidate: c('CLUB', 4),
    answer: true,
    explain: '7 로 지정된 무늬(♣)가 기준 무늬가 됩니다.',
  },
  // §5.2-4 — 맨 위가 조커이고 공격받는 중이 아니면 아무 카드나.
  {
    top: BLACK_JOKER,
    declaredSuit: null,
    attackStack: 0,
    candidate: c('DIAMOND', 4),
    answer: true,
    explain: '맨 위가 조커이고 공격받는 중이 아니면 아무 카드나 낼 수 있습니다.',
  },
  // §6.2 — 다른 숫자로 반격하려면 기준 무늬가 같아야 한다.
  {
    top: c('HEART', 2),
    declaredSuit: null,
    attackStack: 2,
    candidate: c('SPADE', 1),
    answer: false,
    explain: '2 에 다른 숫자(A)로 반격하려면 무늬(♥)가 같아야 합니다. 모든 2, ♥A, 조커는 됩니다.',
  },
  // §6.2 — 조커는 무늬와 무관하고 A 보다 세다.
  {
    top: c('CLUB', 1),
    declaredSuit: null,
    attackStack: 3,
    candidate: BLACK_JOKER,
    answer: true,
    explain: '조커는 A 보다 세고 무늬와 무관해 반격할 수 있습니다. 반격하지 않으면 3장을 먹습니다.',
  },
];

function situation(q: PlayQuestion): string {
  const parts = [`맨 위 ${cardLabel(q.top)}`];
  if (q.declaredSuit) parts.push(`지정 무늬 ${SUIT_SYMBOL[q.declaredSuit]} ${SUIT_LABEL[q.declaredSuit]}`);
  parts.push(q.attackStack > 0 ? `공격받는 중 +${q.attackStack}` : '공격 없음');
  return parts.join(' · ');
}

export function PlayQuiz() {
  const [index, setIndex] = useState(0);
  const [picked, setPicked] = useState<boolean | null>(null);
  const q = PLAY_QUESTIONS[index];

  const feedback =
    picked === null
      ? `${cardLabel(q.candidate)}, 지금 낼 수 있을까요?`
      : picked === q.answer
        ? `정답! ${q.explain}`
        : `아니에요 — ${q.answer ? '낼 수 있습니다' : '낼 수 없습니다'}. ${q.explain}`;

  return (
    <div className="tutorial-practice">
      <p style={{ fontSize: '0.85rem', opacity: 0.7 }}>
        문제 {index + 1} / {PLAY_QUESTIONS.length}
      </p>
      <p>{situation(q)}</p>
      <div style={{ display: 'flex', gap: 16, justifyContent: 'center', alignItems: 'center', margin: '8px 0' }}>
        <OneCardCardChip card={q.top} compact />
        <span aria-hidden>←</span>
        <OneCardCardChip card={q.candidate} compact />
      </div>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
        <Button type="button" size="sm" variant={picked === true ? 'default' : 'outline'} onClick={() => setPicked(true)}>
          낼 수 있다
        </Button>
        <Button type="button" size="sm" variant={picked === false ? 'default' : 'outline'} onClick={() => setPicked(false)}>
          낼 수 없다
        </Button>
      </div>
      <p role="status" aria-live="polite" style={{ marginTop: 12, textAlign: 'center' }}>
        {feedback}
      </p>
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => {
            setIndex((i) => (i + 1) % PLAY_QUESTIONS.length);
            setPicked(null);
          }}
        >
          다음 문제
        </Button>
      </div>
    </div>
  );
}
```

`client/src/features/onecard/tutorial/ReactionPractice.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { RACE_SLOT_COUNT, racePosition } from '../raceSlots';

/** 버튼이 뜨기까지의 대기(ms) — 매번 달라 미리 누를 수 없다. */
export const PRACTICE_MIN_DELAY_MS = 600;
export const PRACTICE_DELAY_SPAN_MS = 1200;
/** 실제 경쟁 창과 같은 3초. */
export const PRACTICE_WINDOW_MS = 3000;

type Phase = 'idle' | 'waiting' | 'showing' | 'done';

interface Props {
  /** 테스트에서 고정한다. */
  random?: () => number;
}

/**
 * D-129 — '원카드!' 반응 연습. **로컬 전용**이다 — 소켓·스토어를 쓰지 않는다(`ReactionPractice.test` 가 원문으로
 * 확인). 실제 경쟁처럼 무작위 대기 뒤 같은 슬롯 표(`raceSlots`)의 무작위 자리에 버튼이 뜨고, 누르기까지 걸린 시간을
 * 보여 준다. 3초가 지나면 실제 창처럼 닫힌다.
 */
export function ReactionPractice({ random = Math.random }: Props) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [pos, setPos] = useState({ left: 50, top: 50 });
  const [result, setResult] = useState<number | null>(null);
  const shownAt = useRef(0);
  const timer = useRef<number | null>(null);

  const clearTimer = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clearTimer, []);

  const start = () => {
    clearTimer();
    setResult(null);
    setPhase('waiting');
    timer.current = window.setTimeout(
      () => {
        const p = racePosition(
          Math.floor(random() * RACE_SLOT_COUNT),
          Math.round(random() * 200 - 100),
          Math.round(random() * 200 - 100),
        );
        // 슬롯 표는 화면 위쪽(세로 5~60%)을 쓴다 — 연습 칸 높이에 맞게 늘인다.
        setPos({ left: p.left, top: 10 + (p.top / 60) * 80 });
        shownAt.current = Date.now();
        setPhase('showing');
        timer.current = window.setTimeout(() => {
          timer.current = null;
          setPhase('done');
        }, PRACTICE_WINDOW_MS);
      },
      PRACTICE_MIN_DELAY_MS + Math.floor(random() * PRACTICE_DELAY_SPAN_MS),
    );
  };

  const press = () => {
    clearTimer();
    setResult(Date.now() - shownAt.current);
    setPhase('done');
  };

  const status =
    phase === 'waiting'
      ? '곧 나타납니다…'
      : phase === 'done'
        ? result !== null
          ? `반응 시간 ${result}ms — 봇은 1.0~2.5초 사이에 누릅니다.`
          : '3초가 지났습니다 — 실제 판이면 벌칙 없이 닫힙니다.'
        : '';

  return (
    <div className="tutorial-practice">
      <p>시작을 누르고, 버튼이 나타나면 최대한 빨리 누르세요. 위치는 매번 바뀝니다.</p>
      <div className="oc-practice-area">
        {phase === 'showing' && (
          <button
            type="button"
            className="oc-race-btn oc-race-call"
            style={{ left: `${pos.left}%`, top: `${pos.top}%` }}
            onClick={press}
          >
            <span className="oc-race-label">원카드!</span>
          </button>
        )}
      </div>
      <p role="status" aria-live="polite" style={{ minHeight: '1.5em', textAlign: 'center' }}>
        {status}
      </p>
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={phase === 'waiting' || phase === 'showing'}
          onClick={start}
        >
          {phase === 'idle' ? '시작' : '다시'}
        </Button>
      </div>
    </div>
  );
}
```

`client/src/features/onecard/tutorial/onecardTutorialSteps.tsx`:

```tsx
import type { TutorialStep } from '@/features/tutorial/types';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';
import { OneCardCardChip } from '../OneCardCardChip';
import { PlayQuiz } from './PlayQuiz';
import { ReactionPractice } from './ReactionPractice';

/**
 * D-129 — 원카드 튜토리얼 12단계. 콘텐츠 출처: `docs/rules-onecard.md` (요약).
 *
 * <p>각 단계의 `source` 는 근거 §다(화면에는 그리지 않는다). 룰을 바꾸면 같은 커밋으로 여기를 고친다 —
 * `onecardTutorialSteps.test` 가 핵심 수치·예외 문구를 붙잡고 있어 먼저 빨개진다.
 */

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const joker = (kind: 'BLACK' | 'COLOR'): OneCardCard => ({ suit: null, rank: 0, joker: kind });

const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/** 카드 줄 — 375px 에서 가로 스크롤 없이 줄바꿈되도록 wrap + compact. */
function CardRow({ cards }: { cards: OneCardCard[] }) {
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 6,
        justifyContent: 'center',
        alignItems: 'center',
        margin: '12px 0',
      }}
    >
      {cards.map((card, i) => (
        <OneCardCardChip key={i} card={card} compact />
      ))}
    </div>
  );
}

export const ONE_CARD_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '미르보드 원카드에 오신 걸 환영합니다',
    source: '§2·§11',
    body: (
      <>
        <p>
          원카드는 <strong>2~6명</strong>이 각자 겨루는 카드 게임입니다. 손패를 <strong>먼저 다 내는 사람</strong>이
          이기고, 나머지는 남은 장수가 적은 순으로 순위가 매겨집니다.
        </p>
        <p>
          카드가 <strong>1장 남는 순간</strong>이 이 게임의 백미입니다 — 화면에 뜨는 버튼을 누가 먼저 누르느냐의
          싸움이죠. 몇 단계로 핵심만 빠르게 익혀볼게요.
        </p>
      </>
    ),
  },
  {
    title: '카드 구성 — 54장',
    source: '§1',
    body: (
      <>
        <p>
          트럼프 52장(♠♥♦♣ × A~K)에 <strong>조커 2장</strong>(흑백·컬러)을 더한 54장입니다.
        </p>
        <CardRow cards={[c('HEART', 2), c('SPADE', 1), joker('BLACK'), joker('COLOR')]} />
        <ul style={LIST}>
          <li>
            <strong>공격</strong> — 2(+2) · A(+3) · 흑백 조커(+5) · 컬러 조커(+7)
          </li>
          <li>
            <strong>특수</strong> — J 건너뛰기 · Q 방향 반전 · K 한 번 더 · 7 무늬 지정
          </li>
          <li>
            <strong>일반</strong> — 3·4·5·6·8·9·10
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '분배와 시작',
    source: '§2·§3',
    body: (
      <>
        <p>
          각자 <strong>7장</strong>씩 받고, 남은 카드가 뽑을 더미가 됩니다. 더미 맨 위 카드를 뒤집어 시작합니다.
        </p>
        <p>
          시작 카드는 언제나 <strong>일반 카드</strong>입니다 — 공격·특수 카드가 나오면 더미 맨 아래로 보내고 다시
          뒤집어요. 첫 차례는 서버가 무작위로 고릅니다.
        </p>
      </>
    ),
  },
  {
    title: '내 차례 — 내기 또는 먹기',
    source: '§4·§7',
    body: (
      <>
        <ul style={LIST}>
          <li>
            카드를 <strong>1장 내거나</strong>, 더미에서 <strong>1장 먹습니다</strong>.
          </li>
          <li>낼 수 있어도 일부러 먹을 수 있습니다.</li>
          <li>
            먹으면 <strong>차례가 끝납니다</strong> — 방금 먹은 카드는 이번 차례에 낼 수 없어요.
          </li>
          <li>턴 제한을 넘기면 먹은 것으로 칩니다.</li>
        </ul>
      </>
    ),
  },
  {
    title: '낼 수 있는 카드',
    source: '§5',
    body: (
      <>
        <p>맨 위 카드와 비교해 다음 중 하나면 낼 수 있습니다.</p>
        <ul style={LIST}>
          <li>
            <strong>같은 무늬</strong> — 7 로 무늬를 지정했다면 그 무늬
          </li>
          <li>
            <strong>같은 숫자</strong>
          </li>
          <li>
            <strong>조커</strong>는 언제나 · 맨 위가 조커면 아무 카드나
          </li>
        </ul>
        <CardRow cards={[c('HEART', 9), c('HEART', 3), c('SPADE', 9)]} />
        <p>
          <strong>7 은 와일드가 아닙니다</strong> — 다른 카드처럼 무늬나 숫자가 맞아야 냅니다.
        </p>
      </>
    ),
  },
  {
    title: '공격과 반격',
    source: '§5.3·§6',
    body: (
      <>
        <p>
          공격 카드를 내면 다음 사람은 <strong>공격받는 중</strong>이 됩니다. 맨 위 공격과{' '}
          <strong>같거나 센</strong> 공격 카드로 반격하면 누적이 다음 사람에게 넘어갑니다.
        </p>
        <p style={{ textAlign: 'center' }}>
          세기: <strong>2 &lt; A &lt; 흑백 조커 &lt; 컬러 조커</strong>
        </p>
        <ul style={LIST}>
          <li>같은 숫자는 무늬와 무관 · 다른 숫자(2 위의 A)는 무늬가 같아야 · 조커는 무늬와 무관</li>
          <li>
            공격받는 중에는 일반 카드와 7·J·Q·K 를 낼 수 없습니다. 반격하지 않으면{' '}
            <strong>누적 장수만큼 먹습니다</strong>.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '특수 카드 — J·Q·K·7',
    source: '§8',
    body: (
      <>
        <CardRow cards={[c('CLUB', 11), c('CLUB', 12), c('CLUB', 13), c('CLUB', 7)]} />
        <ul style={LIST}>
          <li>
            <strong>J</strong> — 다음 사람을 건너뜁니다
          </li>
          <li>
            <strong>Q</strong> — 진행 방향을 뒤집습니다
          </li>
          <li>
            <strong>K</strong> — 같은 사람이 한 번 더 합니다
          </li>
          <li>
            <strong>7</strong> — 낼 때 다음 기준 무늬를 지정합니다
          </li>
        </ul>
        <p>살아 있는 사람이 2명이면 J·Q 도 "한 번 더"로 동작합니다.</p>
      </>
    ),
  },
  {
    title: '원카드! 잡기!',
    source: '§9',
    body: (
      <>
        <p>
          카드를 내서 <strong>1장이 남으면 3초 경쟁</strong>이 열립니다. 화면 어딘가 — 매번 다른 자리 — 에 버튼이
          뜹니다.
        </p>
        <ul style={LIST}>
          <li>
            1장 남은 사람은 <strong>"원카드!"</strong> — 먼저 누르면 안전합니다
          </li>
          <li>
            다른 사람은 <strong>"잡기!"</strong> — 먼저 누르면 상대가 <strong>벌칙 1장</strong>을 먹습니다
          </li>
          <li>3초 동안 아무도 안 누르면 벌칙 없이 닫힙니다</li>
        </ul>
        <p>
          서버에 먼저 도착한 누름이 이깁니다. 봇도 1.0~2.5초 사이에 누릅니다. 단축키는 없어요 — 버튼을 찾아
          누르는 것이 이 게임입니다.
        </p>
      </>
    ),
  },
  {
    title: '파산과 탈락',
    source: '§10',
    body: (
      <>
        <ul style={LIST}>
          <li>
            먹은 뒤 손패가 <strong>20장 이상</strong>이면 <strong>파산</strong> — 탈락합니다. 벌칙 1장으로는 파산하지
            않습니다.
          </li>
          <li>
            게임 중에 나가면 <strong>탈주</strong>로 탈락하고 최하위가 됩니다. 이미 탈락한 뒤에 나가는 것은 탈주가
            아닙니다.
          </li>
          <li>탈락한 사람의 손패는 뽑을 더미 맨 아래로 들어가고, 차례에서 빠집니다.</li>
        </ul>
      </>
    ),
  },
  {
    title: '종료와 순위',
    source: '§11',
    body: (
      <>
        <ul style={LIST}>
          <li>
            누군가 <strong>마지막 카드를 내면</strong> 바로 끝 — 그 사람이 1등입니다.
          </li>
          <li>
            나머지는 <strong>남은 장수가 적은 순</strong>, 그 아래 파산자, 맨 아래 탈주자입니다. 장수가 같으면 공동
            순위(1, 1, 3)입니다.
          </li>
          <li>
            한 명만 남거나 남은 사람이 모두 봇이면 끝납니다. 더 진행할 수 없으면(전원 패스) 남은 장수로 순위를
            매깁니다.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '연습 — 낼 수 있을까?',
    source: '§5·§6.2',
    body: (
      <>
        <p>맨 위 카드와 상황을 보고, 오른쪽 카드를 지금 낼 수 있는지 골라 보세요.</p>
        <PlayQuiz />
      </>
    ),
  },
  {
    title: '연습 — 원카드! 반응',
    source: '§9',
    body: (
      <>
        <p>
          마지막으로 버튼 누르기를 연습해 봐요. 준비됐으면 '시작하기'로 판에 들어가세요 — 규칙은 게임판의{' '}
          <strong>규칙</strong> 버튼으로 언제든 다시 볼 수 있습니다.
        </p>
        <ReactionPractice />
      </>
    ),
  },
];
```

`client/src/features/onecard/tutorial/onecardTutorial.ts`:

```ts
import type { GameTutorial } from '@/features/tutorial/types';
import { ONE_CARD_TUTORIAL_STEPS } from './onecardTutorialSteps';

/**
 * D-129 — 원카드 튜토리얼 선언. 열람 키는 게임 전용이다(다른 게임 키와 격리).
 *
 * <p>`bodyClassName: 'oc-tokens'` — 다이얼로그는 body 포털이라 `.oc-table` 밖이다. 카드 칩·연습 버튼이 쓰는 색
 * 토큰을 본문 전체에 한 번에 푼다.
 */
export const ONE_CARD_TUTORIAL: GameTutorial = {
  steps: ONE_CARD_TUTORIAL_STEPS,
  description: '원카드 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.one_card.seen.v1',
  bodyClassName: 'oc-tokens',
};
```

- [ ] **Step 5: 구현 — 레지스트리와 위키 링크**

`client/src/features/tutorial/gameTutorials.ts`:

```diff
 import { TICHU_TUTORIAL } from '@/features/tichu/tutorial/tichuTutorial';
 import { SKULL_KING_TUTORIAL } from '@/features/skullking/tutorial/skullkingTutorial';
+import { ONE_CARD_TUTORIAL } from '@/features/onecard/tutorial/onecardTutorial';
 import type { GameTutorial } from './types';
 
 /**
```

```diff
 export const GAME_TUTORIALS: Readonly<Record<string, GameTutorial>> = {
   TICHU: TICHU_TUTORIAL,
   SKULL_KING: SKULL_KING_TUTORIAL,
+  ONE_CARD: ONE_CARD_TUTORIAL,
 };
 
 /** 서버 게임 id(`TICHU` 등)로 조회한다. 대소문자는 `loadGame` 과 같이 대문자로 정규화한다. */
```

`client/src/features/lobby/gameWiki.ts`:

```diff
   tichu: 'https://en.wikipedia.org/wiki/Tichu',
   // 스컬킹은 영문 위키백과에 문서가 없다(2026-08 확인, `Skull_King` → 404). BGG 를 쓴다.
   skull_king: 'https://boardgamegeek.com/boardgame/150145/skull-king',
+  // 원카드는 한국식 하우스 룰 게임이라 영문 문서가 없다. 나무위키 '원카드' 문서(퍼센트 인코딩).
+  one_card: 'https://namu.wiki/w/%EC%9B%90%EC%B9%B4%EB%93%9C',
 };
 
 export function gameWikiUrl(gameId: string): string | undefined {
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix client run test -- raceSlots onecard/tutorial gameTutorials gameWiki`
Expected: PASS — 42건(슬롯 5 · 단계 14 · 퀴즈 5 · 반응 연습 4 · 레지스트리 11 · 위키 3).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  56 passed (56)` · `Tests  515 passed (515)`.

- [ ] **Step 7: 커밋**

```bash
git add client/src/features/onecard/OneCardCardChip.tsx \
  client/src/features/onecard/raceSlots.ts \
  client/src/features/onecard/raceSlots.test.ts \
  client/src/features/onecard/tutorial/onecardTutorial.ts \
  client/src/features/onecard/tutorial/onecardTutorialSteps.tsx \
  client/src/features/onecard/tutorial/onecardTutorialSteps.test.tsx \
  client/src/features/onecard/tutorial/PlayQuiz.tsx \
  client/src/features/onecard/tutorial/PlayQuiz.test.tsx \
  client/src/features/onecard/tutorial/ReactionPractice.tsx \
  client/src/features/onecard/tutorial/ReactionPractice.test.tsx \
  client/src/features/tutorial/gameTutorials.ts \
  client/src/features/tutorial/gameTutorials.test.ts \
  client/src/features/lobby/gameWiki.ts \
  client/src/features/lobby/gameWiki.test.ts
git commit -m "feat(D-129): 원카드 카드 칩·경쟁 슬롯·튜토리얼(규칙·퀴즈·반응 연습) + 레지스트리·위키 링크

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: 클라 — 게임판

**Files:**
- Create: `client/src/components/seatOrder.ts`, `client/src/features/onecard/OneCardSeat.tsx`, `client/src/features/onecard/OneCardCenter.tsx`, `client/src/features/onecard/OneCardHand.tsx`, `client/src/features/onecard/RaceButton.tsx`,
  `client/src/features/onecard/OneCardMatchEnd.tsx`, `client/src/features/onecard/OneCardTable.tsx`, `client/src/features/onecard/OneCardTable.test.tsx`
- Modify: `client/src/features/skullking/seatLayout.ts`(좌석 순서·최소 폭을 공용 모듈에서 다시 내보낸다)

**Interfaces:**
- Consumes: Task 2·3·4 전부, `ws/useStompRoom`(`{connected, sendAction, sendChat, chatPanelOpenRef}`), `components/ReconnectBanner`,
  `features/chat/RoomChat`·`roomChatStore`, `features/tutorial/TutorialDialog`·`useTutorialGate`, `api/avatar`·`components/avatarGlyph`.
- Produces: `viewOrder(seatCount, mySeat)`·`seatMinWidth(seatCount)`(공용 — 스컬킹 `seatLayout` 이 다시 내보내 기존 import 무변경),
  `OneCardTable({roomId, playerIds, myUserId, spectator?, botSeats?, usernames?, turnSeconds?, spectatorCount?, onExit?, roomFinished?})`
  — 스컬킹 게임판과 같은 props 계약(Task 7 이 쓴다), `PRESS_RETRY_DELAY_MS`(120)·`LATE_NOTICE_MS`(1500). 보내는 액션:
  `{'@action': 'PLAY_CARD', card, declaredSuit?}`·`{'@action': 'DRAW'}`·`{'@action': 'CALL_ONE_CARD' | 'CATCH', raceId}`.

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/features/onecard/OneCardTable.test.tsx`:

```tsx
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LATE_NOTICE_MS, OneCardTable, PRESS_RETRY_DELAY_MS } from './OneCardTable';
import { useOneCardStore } from './onecardStore';
import { useAuthStore } from '@/features/auth/authStore';
import type {
  OneCardCard,
  OneCardMatchResult,
  OneCardRaceView,
  OneCardSeatView,
  OneCardSuit,
  OneCardTableView,
} from '@/types/onecard';

// 소켓만 모킹하고 스토어는 실물을 seed 한다 (스컬킹 게임판 테스트와 같은 방식).
const sendAction = vi.fn();
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    chatPanelOpenRef: { current: false },
  }),
}));

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const seatOf = (n: number, over: Partial<OneCardSeatView> = {}): OneCardSeatView => ({
  seat: n,
  handCount: 5,
  eliminated: null,
  ...over,
});

const NOW = 1_000_000;

function seed(opts: {
  seatCount: number;
  mySeat: number;
  hand?: OneCardCard[];
  turnSeat?: number;
  topCard?: OneCardCard | null;
  declaredSuit?: OneCardSuit | null;
  attackStack?: number;
  seats?: OneCardSeatView[];
  race?: OneCardRaceView | null;
  result?: OneCardMatchResult | null;
}) {
  const table: OneCardTableView = {
    phase: opts.result ? 'ENDED' : opts.race ? 'RACE' : 'PLAYING',
    seats: opts.seats ?? Array.from({ length: opts.seatCount }, (_, i) => seatOf(i)),
    topCard: opts.topCard === undefined ? c('HEART', 9) : opts.topCard,
    declaredSuit: opts.declaredSuit ?? null,
    attackStack: opts.attackStack ?? 0,
    direction: 1,
    turnSeat: opts.turnSeat ?? -1,
    drawPileCount: 30,
    race: opts.race ?? null,
    result: opts.result ?? null,
  };
  useOneCardStore.getState().reset('r-1');
  useOneCardStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: table.phase,
    eventSeq: 1,
    tableView: table,
    privateHand:
      opts.mySeat >= 0 ? { seat: opts.mySeat, hand: opts.hand ?? [], handVersion: 1 } : null,
    disconnectedSeats: [],
    chips: null,
  });
}

const RACE: OneCardRaceView = {
  raceId: 7,
  ownerSeat: 1,
  slot: 2,
  jitterX: 30,
  jitterY: -40,
  windowMillis: 3000,
  remainingMillis: 3000,
};

const playerIds = (n: number) => Array.from({ length: n }, (_, i) => 100 + i);

function renderTable(over: Partial<Parameters<typeof OneCardTable>[0]> = {}) {
  const n = over.playerIds?.length ?? 4;
  return render(<OneCardTable roomId="r-1" playerIds={playerIds(n)} myUserId={100} {...over} />);
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  vi.setSystemTime(NOW);
  sendAction.mockReset();
  useAuthStore.setState({ token: 'tok' } as never);
  useOneCardStore.getState().reset('r-1');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('OneCardTable — 좌석', () => {
  it.each([2, 4, 6])('%i인이면 상대 좌석 %i-1 개와 내 정보줄을 그린다', (n) => {
    seed({ seatCount: n, mySeat: 0 });
    const { container } = renderTable({ playerIds: playerIds(n) });

    expect(container.querySelectorAll('.oc-seat')).toHaveLength(n - 1);
    expect(container.querySelector('.oc-me')).not.toBeNull();
  });

  it('상대 좌석은 이름과 장수만 보여 주고, 1장이면 표시를 세운다', () => {
    seed({ seatCount: 3, mySeat: 0, seats: [seatOf(0), seatOf(1, { handCount: 1 }), seatOf(2)] });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    const one = container.querySelector('[data-seat="1"]') as HTMLElement;
    expect(within(one).getByText('1장!')).toBeInTheDocument();
    expect(container.querySelector('[data-seat="2"]')!.textContent).not.toContain('1장!');
  });

  it('탈락한 좌석에는 사유를 단다', () => {
    seed({
      seatCount: 3,
      mySeat: 0,
      seats: [seatOf(0), seatOf(1, { eliminated: 'BANKRUPT', handCount: 0 }), seatOf(2)],
    });
    const { container } = renderTable({ playerIds: [100, 101, 102] });

    expect(within(container.querySelector('[data-seat="1"]') as HTMLElement).getByText('파산')).toBeInTheDocument();
  });

  it('관전자는 모든 좌석을 보고 손패·버튼이 없다', () => {
    seed({ seatCount: 4, mySeat: -1, turnSeat: 2 });
    const { container } = renderTable({ spectator: true });

    expect(container.querySelectorAll('.oc-seat')).toHaveLength(4);
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
    expect(screen.getByText('관전 모드')).toBeInTheDocument();
  });
});

describe('OneCardTable — 내기·먹기', () => {
  const HAND = [c('CLUB', 4), c('HEART', 3), c('SPADE', 9), c('CLUB', 7)];

  it('손패를 무늬·숫자 순으로 보여 주고, 내 차례면 낼 수 없는 카드를 흐리게 한다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    const hand = screen.getByRole('region', { name: '내 손패' });
    const names = within(hand)
      .getAllByRole('button', { pressed: false })
      .map((b) => b.getAttribute('aria-label'));
    expect(names).toEqual(['스페이드 9', '하트 3', '클로버 4', '클로버 7']);
    expect(within(hand).getByRole('button', { name: '클로버 4' })).toHaveClass('oc-card-dimmed');
    expect(within(hand).getByRole('button', { name: '하트 3' })).not.toHaveClass('oc-card-dimmed');
  });

  it('카드를 고르고 내면 그 카드를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '하트 3' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 내기' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PLAY_CARD', card: c('HEART', 3) });
  });

  it('7 은 무늬를 골라야 낼 수 있고, 고른 무늬를 함께 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('CLUB', 9) });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '클로버 7' }));
    expect(screen.getByRole('button', { name: '무늬를 고르세요' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '하트' }));
    fireEvent.click(screen.getByRole('button', { name: '카드 내기' }));

    expect(sendAction).toHaveBeenCalledWith({
      '@action': 'PLAY_CARD',
      card: c('CLUB', 7),
      declaredSuit: 'HEART',
    });
  });

  it('먹기 버튼은 공격받는 중이면 누적 장수를 보여 주고 DRAW 를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND, topCard: c('HEART', 2), attackStack: 4 });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByText('공격받는 중 +4')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '먹기 (4장)' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'DRAW' });
  });

  it('내 차례가 아니면 내기·먹기를 막는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('button', { name: '내 차례 아님' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '먹기 (1장)' })).toBeDisabled();
  });

  it('가운데에 맨 위 카드·지정 무늬·더미 장수·차례를 보여 준다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, topCard: c('HEART', 7), declaredSuit: 'CLUB' });
    renderTable({ playerIds: [100, 101, 102] });

    const center = screen.getByRole('region', { name: '테이블' });
    expect(within(center).getByRole('img', { name: '하트 7' })).toBeInTheDocument();
    expect(within(center).getByText('지정 ♣ 클로버')).toBeInTheDocument();
    expect(within(center).getByText('뽑을 더미 30장')).toBeInTheDocument();
    expect(within(center).getByText('#101 차례')).toBeInTheDocument();
  });

  it('거절 문구를 보여 주고 닫을 수 있다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: HAND });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => useOneCardStore.getState().setError('지금 낼 수 없는 카드입니다.'));
    expect(screen.getByRole('alert')).toHaveTextContent('지금 낼 수 없는 카드입니다.');

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('OneCardTable — 원카드 경쟁', () => {
  it('주인에게는 "원카드!" — 누르면 창 번호와 함께 CALL_ONE_CARD 를 보내고 다시 누를 수 없다', () => {
    seed({ seatCount: 3, mySeat: 1, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    const button = screen.getByRole('button', { name: '원카드!' });
    fireEvent.click(button);

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'CALL_ONE_CARD', raceId: 7 });
    expect(screen.getByRole('button', { name: '원카드!' })).toBeDisabled();
  });

  it('다른 사람에게는 "잡기!" — CATCH 를 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3), c('CLUB', 5)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));

    expect(sendAction).toHaveBeenCalledWith({ '@action': 'CATCH', raceId: 7 });
  });

  it('버튼은 서버가 고른 슬롯·지터 자리에 뜬다 — 자동 포커스는 주지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    const button = screen.getByRole('button', { name: '잡기!' });
    // 슬롯 2(78%, 18%) + 지터(+30% × 10, −40% × 6) = (81%, 15.6%)
    expect(button.style.left).toContain('81%');
    expect(button.style.top).toBe('15.6%');
    expect(document.activeElement).not.toBe(button);
  });

  it('창이 열린 동안 내기·먹기 버튼을 막는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('button', { name: '내 차례 아님' })).toBeDisabled();
  });

  it('관전자와 탈락자에게는 버튼이 없고, 창이 열린 사실은 알림으로 읽힌다', () => {
    seed({ seatCount: 3, mySeat: -1, race: RACE });
    const { unmount } = renderTable({ playerIds: [100, 101, 102], spectator: true });
    expect(screen.queryByRole('button', { name: /원카드!|잡기!/ })).toBeNull();
    expect(screen.getByText('#101 카드 1장! 경쟁 중')).toBeInTheDocument();
    unmount();

    seed({
      seatCount: 3,
      mySeat: 2,
      seats: [seatOf(0), seatOf(1), seatOf(2, { eliminated: 'BANKRUPT', handCount: 0 })],
      race: RACE,
    });
    renderTable({ playerIds: [100, 101, 102] });
    expect(screen.queryByRole('button', { name: /원카드!|잡기!/ })).toBeNull();
  });

  it('BUSY 로 거절되면 잠시 뒤 같은 누름을 다시 보낸다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      useOneCardStore.getState().notePressRejected('BUSY');
    });
    expect(sendAction).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(PRESS_RETRY_DELAY_MS);
    });
    expect(sendAction).toHaveBeenCalledTimes(2);
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'CATCH', raceId: 7 });
  });

  it('이미 닫힌 창이면 "늦었어요"를 잠깐 보여 준다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '잡기!' }));
    act(() => {
      useOneCardStore.getState().notePressRejected('NO_RACE');
    });
    expect(screen.getByText('늦었어요 — 이미 끝난 경쟁입니다')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(LATE_NOTICE_MS);
    });
    expect(screen.queryByText('늦었어요 — 이미 끝난 경쟁입니다')).toBeNull();
  });

  it('창이 닫히면 결과를 한 줄로 보여 주고 알림도 같은 문장이다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], race: RACE });
    renderTable({ playerIds: [100, 101, 102] });

    act(() => {
      useOneCardStore.getState().applyEvent({
        type: 'RACE_RESOLVED',
        payload: { raceId: 7, outcome: 'CAUGHT', bySeat: 2 },
      });
    });

    expect(screen.getAllByText('잡기 성공: #102 → #101 벌칙 1장')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '잡기!' })).toBeNull();
  });
});

describe('OneCardTable — 종료·나가기', () => {
  const RESULT: OneCardMatchResult = {
    reason: 'FINISHED',
    standings: [
      { seat: 1, rank: 1, cardsLeft: 0, status: 'FINISHED' },
      { seat: 0, rank: 2, cardsLeft: 3, status: 'ALIVE' },
      { seat: 2, rank: 3, cardsLeft: 21, status: 'BANKRUPT' },
    ],
  };

  it('결과가 오면 순위를 보여 주고 손패 입력을 내린다', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)], result: RESULT });
    renderTable({ playerIds: [100, 101, 102] });

    const end = screen.getByRole('region', { name: '판 종료' });
    expect(within(end).getByRole('heading')).toHaveTextContent('판 종료');
    const rows = within(end).getAllByRole('listitem').map((li) => li.textContent);
    expect(rows).toEqual(['1위#1010장다 냄', '2위#100 (나)3장', '3위#10221장파산']);
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
  });

  it('내가 1등이면 승리 표시', () => {
    seed({ seatCount: 3, mySeat: 1, result: RESULT });
    renderTable({ playerIds: [100, 101, 102] });

    expect(screen.getByRole('heading')).toHaveTextContent('승리 🎉');
  });

  it('게임 중 나가기는 탈주 확인을 묻고, 취소하면 나가지 않는다', () => {
    seed({ seatCount: 3, mySeat: 0, turnSeat: 1, hand: [c('HEART', 3)] });
    const onExit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderTable({ playerIds: [100, 101, 102], onExit });

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));
    expect(confirm).toHaveBeenCalled();
    expect(onExit).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('끝난 판이나 이미 탈락한 좌석은 묻지 않고 나간다', () => {
    seed({ seatCount: 3, mySeat: 0, result: RESULT });
    const onExit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm');
    renderTable({ playerIds: [100, 101, 102], onExit });

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));
    expect(confirm).not.toHaveBeenCalled();
    expect(onExit).toHaveBeenCalledTimes(1);
    confirm.mockRestore();
  });

  it('방은 끝났는데 결과가 없으면 종료 안내와 메인으로 버튼', () => {
    seed({ seatCount: 3, mySeat: 0, hand: [c('HEART', 3)] });
    const onExit = vi.fn();
    renderTable({ playerIds: [100, 101, 102], onExit, roomFinished: true });

    expect(screen.getByText('게임이 종료되었습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '내 손패' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '메인으로' }));
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('규칙 버튼은 원카드 튜토리얼을 연다', () => {
    seed({ seatCount: 3, mySeat: 0 });
    renderTable({ playerIds: [100, 101, 102] });

    fireEvent.click(screen.getByRole('button', { name: '규칙' }));
    expect(screen.getByText('미르보드 원카드에 오신 걸 환영합니다')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- OneCardTable`
Expected: FAIL — `Failed to resolve import "./OneCardTable"`.

- [ ] **Step 3: 구현 — 좌석 순서 공용화**

`client/src/components/seatOrder.ts`:

```ts
/**
 * 가변 좌석 게임판의 순수 배치 계산 — 스컬킹(2~8인, D-103 Row-Flow)과 원카드(2~6인, D-129)가 같이 쓴다. DOM 을
 * 모른다 — 전수 테스트가 jsdom 없이 돈다(`features/skullking/seatLayout.test.ts`).
 */

/**
 * 상대 좌석을 화면에 늘어놓을 순서. **내 다음 차례부터 좌→우**라 진행 방향이 읽힌다.
 *
 * @param mySeat 관전자는 -1 — 그때는 내 좌석을 뺄 것이 없으므로 전 좌석을 돌려준다.
 */
export function viewOrder(seatCount: number, mySeat: number): number[] {
  if (seatCount <= 0) return [];
  if (mySeat < 0 || mySeat >= seatCount) {
    return Array.from({ length: seatCount }, (_, i) => i);
  }
  return Array.from(
    { length: seatCount - 1 },
    (_, i) => (mySeat + 1 + i) % seatCount,
  );
}

/**
 * 좌석 카드의 최소 폭. `repeat(auto-fit, minmax(이 값, 168px))` 에 꽂으면 인원이 늘수록
 * 한 행에 더 많이 들어가고, 넘치면 **폭 미디어 쿼리 없이** 자동 줄바꿈된다.
 */
export function seatMinWidth(seatCount: number): string {
  if (seatCount <= 4) return '132px';
  if (seatCount <= 6) return '116px';
  return '100px';
}
```

`client/src/features/skullking/seatLayout.ts`:

```diff
 
 /**
  * 2~8인 가변 좌석의 순수 배치 계산 (D-103, Row-Flow). DOM 을 모른다 — 전수 테스트가
- * jsdom 없이 돈다.
+ * jsdom 없이 돈다. 좌석 순서·최소 폭은 원카드도 쓰는 공용 모듈에서 다시 내보낸다(D-129).
  */
 
-/**
- * 상대 좌석을 화면에 늘어놓을 순서. **내 다음 차례부터 좌→우**라 진행 방향이 읽힌다.
- *
- * @param mySeat 관전자는 -1 — 그때는 내 좌석을 뺄 것이 없으므로 전 좌석을 돌려준다.
- */
-export function viewOrder(seatCount: number, mySeat: number): number[] {
-  if (seatCount <= 0) return [];
-  if (mySeat < 0 || mySeat >= seatCount) {
-    return Array.from({ length: seatCount }, (_, i) => i);
-  }
-  return Array.from(
-    { length: seatCount - 1 },
-    (_, i) => (mySeat + 1 + i) % seatCount,
-  );
-}
-
-/**
- * 좌석 카드의 최소 폭. `repeat(auto-fit, minmax(이 값, 168px))` 에 꽂으면 인원이 늘수록
- * 한 행에 더 많이 들어가고, 넘치면 **폭 미디어 쿼리 없이** 자동 줄바꿈된다.
- */
-export function seatMinWidth(seatCount: number): string {
-  if (seatCount <= 4) return '132px';
-  if (seatCount <= 6) return '116px';
-  return '100px';
-}
+export { seatMinWidth, viewOrder } from '@/components/seatOrder';
 
 /** 좌석별 accent 색 — 트릭 레일에서 "누가 냈는지"를 잇는 단서 중 하나. */
 const ACCENTS = [
```

- [ ] **Step 4: 구현 — 게임판 조각**

`client/src/features/onecard/OneCardSeat.tsx`:

```tsx
import { avatarSrc } from '@/api/avatar';
import { animalFor } from '@/components/avatarGlyph';
import type { EliminationReason, OneCardSeatView } from '@/types/onecard';

const ACCENTS = [
  'var(--oc-accent-0)',
  'var(--oc-accent-1)',
  'var(--oc-accent-2)',
  'var(--oc-accent-3)',
  'var(--oc-accent-4)',
  'var(--oc-accent-5)',
];

/** 좌석별 accent 색 — 좌석 카드와 내 정보줄 테두리를 잇는다. */
export function seatAccent(seat: number): string {
  return ACCENTS[((seat % ACCENTS.length) + ACCENTS.length) % ACCENTS.length];
}

export const ELIMINATED_LABEL: Record<EliminationReason, string> = {
  BANKRUPT: '파산',
  DESERTED: '탈주',
};

interface Props {
  seat: OneCardSeatView;
  userId?: number;
  username?: string;
  isBot?: boolean;
  isTurn?: boolean;
  isDisconnected?: boolean;
}

/**
 * 상대 좌석 하나 — 이름과 남은 손패 **장수**만 보인다(State Hiding). 1장 남으면 표시를 세우고, 탈락하면 사유를 단다.
 * 스컬킹처럼 정상 흐름의 `auto-fit` 그리드에 놓여 2~6인이 폭 미디어 없이 줄바꿈된다.
 */
export function OneCardSeat({
  seat,
  userId,
  username,
  isBot = false,
  isTurn = false,
  isDisconnected = false,
}: Props) {
  const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
  const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);
  const out = seat.eliminated !== null;
  const last = !out && seat.handCount === 1;

  const className = [
    'oc-seat',
    isTurn ? 'oc-seat-turn' : '',
    out ? 'oc-seat-out' : '',
    isDisconnected ? 'oc-seat-offline' : '',
    last ? 'oc-seat-last' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={className}
      style={{ ['--oc-accent' as string]: seatAccent(seat.seat) }}
      data-seat={seat.seat}
    >
      <div className="oc-seat-top">
        <span className="oc-seat-avatar" aria-hidden>
          {!isBot && userId != null ? (
            <img
              src={avatarSrc(userId)}
              alt=""
              draggable={false}
              onError={(e) => {
                (e.currentTarget as HTMLImageElement).style.display = 'none';
              }}
            />
          ) : null}
          <span className="oc-seat-glyph">{glyph}</span>
        </span>
        <span className="oc-seat-name" title={name}>
          {name}
        </span>
      </div>

      <div className="oc-seat-count" title="남은 손패">
        <span className="oc-seat-count-num">{seat.handCount}</span>
        <span className="oc-seat-count-unit">장</span>
        {last && <span className="oc-seat-one">1장!</span>}
      </div>

      {(out || isDisconnected) && (
        <span className="status-tag oc-seat-tag">
          {out ? ELIMINATED_LABEL[seat.eliminated!] : '연결 끊김'}
        </span>
      )}
    </div>
  );
}
```

`client/src/features/onecard/OneCardCenter.tsx`:

```tsx
import { SUIT_LABEL, SUIT_SYMBOL, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import type { LastRace } from './onecardStore';
import { OneCardCardChip } from './OneCardCardChip';

/** 방금 닫힌 경쟁의 한 줄 안내 — 화면과 스크린리더 알림이 같은 문장을 쓴다. */
export function raceResultText(race: LastRace, nameOf: (seat: number) => string): string {
  switch (race.outcome) {
    case 'CALLED':
      return `원카드! ${nameOf(race.bySeat)} 안전`;
    case 'CAUGHT':
      return race.ownerSeat >= 0
        ? `잡기 성공: ${nameOf(race.bySeat)} → ${nameOf(race.ownerSeat)} 벌칙 1장`
        : `잡기 성공: ${nameOf(race.bySeat)}`;
    case 'EXPIRED':
      return '시간 초과 — 벌칙 없음';
    case 'CANCELLED':
      return '탈주로 경쟁이 취소됐습니다';
  }
}

interface Props {
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  drawPileCount: number;
  /** 지금 차례인 사람 이름, 없으면 null. */
  turnName: string | null;
  raceOpen: boolean;
  lastRace: LastRace | null;
  late: boolean;
  nameOf: (seat: number) => string;
}

/** 가운데 — 뽑을 더미, 맨 위 카드와 지정 무늬, 공격 누적, 진행 방향, 차례, 경쟁 결과. 모두 공개 정보다. */
export function OneCardCenter({
  topCard,
  declaredSuit,
  attackStack,
  direction,
  drawPileCount,
  turnName,
  raceOpen,
  lastRace,
  late,
  nameOf,
}: Props) {
  return (
    <section className="oc-center" aria-label="테이블">
      <div className="oc-piles">
        <div className="oc-pile" title="뽑을 더미">
          <span className="oc-pile-back" aria-hidden />
          <span className="oc-pile-count">뽑을 더미 {drawPileCount}장</span>
        </div>
        <div className="oc-top">
          {topCard ? (
            <OneCardCardChip card={topCard} />
          ) : (
            <span className="oc-top-empty">—</span>
          )}
          {declaredSuit && (
            <span className="oc-badge oc-declared">
              지정 {SUIT_SYMBOL[declaredSuit]} {SUIT_LABEL[declaredSuit]}
            </span>
          )}
        </div>
      </div>

      <div className="oc-center-info">
        {attackStack > 0 && <span className="oc-badge oc-attack">공격 +{attackStack}</span>}
        <span className="oc-badge">{direction >= 0 ? '진행 → 정방향' : '진행 ← 역방향'}</span>
        {raceOpen ? (
          <span className="oc-badge oc-race-status">원카드 경쟁 중</span>
        ) : (
          turnName && <span className="oc-badge">{turnName} 차례</span>
        )}
      </div>

      {lastRace && !raceOpen && <p className="oc-race-result">{raceResultText(lastRace, nameOf)}</p>}
      {late && <p className="oc-race-late">늦었어요 — 이미 끝난 경쟁입니다</p>}
    </section>
  );
}
```

`client/src/features/onecard/OneCardHand.tsx`:

```tsx
import {
  SUIT_LABEL,
  SUIT_SYMBOL,
  cardKey,
  type OneCardCard,
  type OneCardSuit,
} from '@/types/onecard';
import { canPlay, drawCount, isSuitChange, sortForDisplay } from './onecardRules';
import { OneCardCardChip } from './OneCardCardChip';

const SUITS: OneCardSuit[] = ['SPADE', 'HEART', 'DIAMOND', 'CLUB'];

interface Props {
  hand: OneCardCard[];
  selectedKey: string | null;
  suitChoice: OneCardSuit | null;
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  myTurn: boolean;
  raceOpen: boolean;
  onSelect: (key: string | null) => void;
  onSuit: (suit: OneCardSuit) => void;
  onPlay: () => void;
  onDraw: () => void;
}

/**
 * 내 손패 + 내기·먹기. 스컬킹처럼 '고르기 → 내기' 2단이다(잘못 낸 카드는 되돌릴 수 없다). 7 을 고르면 지정할 무늬를
 * 함께 고른다. 낼 수 없는 카드는 내 차례에만 흐리게 한다 — 표시 전용이고 판정은 서버다. 경쟁 창이 열린 동안은
 * 서버가 내기·먹기를 거절하므로(`RACE_IN_PROGRESS`) 버튼을 미리 막는다.
 */
export function OneCardHand({
  hand,
  selectedKey,
  suitChoice,
  topCard,
  declaredSuit,
  attackStack,
  myTurn,
  raceOpen,
  onSelect,
  onSuit,
  onPlay,
  onDraw,
}: Props) {
  const selected = selectedKey ? (hand.find((c) => cardKey(c) === selectedKey) ?? null) : null;
  const needSuit = selected !== null && isSuitChange(selected);
  const canAct = myTurn && !raceOpen;
  const canSubmit = canAct && selected !== null && (!needSuit || suitChoice !== null);

  const playLabel = !myTurn
    ? '내 차례 아님'
    : raceOpen
      ? '경쟁 중'
      : selected === null
        ? '카드를 고르세요'
        : needSuit && suitChoice === null
          ? '무늬를 고르세요'
          : '카드 내기';

  return (
    <section className="my-hand oc-hand" aria-label="내 손패">
      <div className="hand-cards oc-hand-cards">
        {sortForDisplay(hand).map((card) => {
          const key = cardKey(card);
          return (
            <OneCardCardChip
              key={key}
              card={card}
              selected={selectedKey === key}
              dimmed={canAct && !canPlay(card, topCard, declaredSuit, attackStack)}
              onClick={() => onSelect(selectedKey === key ? null : key)}
            />
          );
        })}
        {hand.length === 0 && <span className="oc-hand-empty">손패 없음</span>}
      </div>

      <div className="action-bar oc-actions">
        {needSuit && (
          <div className="oc-suits" role="group" aria-label="7 로 지정할 무늬">
            {SUITS.map((suit) => (
              <button
                key={suit}
                type="button"
                className={`oc-suit-opt${suit === 'HEART' || suit === 'DIAMOND' ? ' oc-suit-red' : ''}${
                  suitChoice === suit ? ' selected' : ''
                }`}
                aria-pressed={suitChoice === suit}
                aria-label={SUIT_LABEL[suit]}
                onClick={() => onSuit(suit)}
              >
                {SUIT_SYMBOL[suit]}
              </button>
            ))}
          </div>
        )}
        <button type="button" className="oc-play" disabled={!canSubmit} onClick={onPlay}>
          {playLabel}
        </button>
        <button type="button" className="oc-draw" disabled={!canAct} onClick={onDraw}>
          먹기 ({drawCount(attackStack)}장)
        </button>
      </div>
    </section>
  );
}
```

`client/src/features/onecard/RaceButton.tsx`:

```tsx
import type { OneCardClientRace, PressAction } from './onecardStore';
import { racePosition } from './raceSlots';

interface Props {
  race: OneCardClientRace;
  mySeat: number;
  /** 내 누름이 응답을 기다리는 중 — 중복 누름을 막는다. */
  pending: boolean;
  onPress: (action: PressAction) => void;
}

/**
 * 외치기 경쟁 버튼 (설계서 §4.10). 화면 위 오버레이 레이어에, 서버가 고른 슬롯 + 지터 위치에 뜬다. 주인은 "원카드!",
 * 나머지 살아 있는 플레이어는 "잡기!" — 관전자·탈락자에게는 게임판이 이 컴포넌트를 그리지 않는다.
 *
 * <p>**자동 포커스와 단축키를 두지 않는다.** 둘 다 사실상 "위치와 무관하게 즉시 누르기"라 무작위 위치의 의미와
 * 레이팅 공정성을 깬다. 진짜 `<button>` 이라 탭·클릭·보조기기로 누를 수 있고, 창이 열린 사실은 게임판의 aria-live
 * 영역이 알린다. 막대는 창 끝까지 남은 시간이다(이 클라 시계 — 판정은 서버).
 */
export function RaceButton({ race, mySeat, pending, onPress }: Props) {
  const owner = mySeat === race.ownerSeat;
  const { left, top } = racePosition(race.slot, race.jitterX, race.jitterY);
  const remaining = Math.max(0, race.closesAt - Date.now());
  const start = race.windowMillis > 0 ? Math.min(1, remaining / race.windowMillis) : 0;

  return (
    <div className="oc-race-layer">
      <button
        type="button"
        className={`oc-race-btn ${owner ? 'oc-race-call' : 'oc-race-catch'}`}
        style={{
          left: `clamp(var(--oc-race-half-w), ${left}%, calc(100% - var(--oc-race-half-w)))`,
          top: `${top}%`,
        }}
        disabled={pending}
        onClick={() => onPress(owner ? 'CALL_ONE_CARD' : 'CATCH')}
      >
        <span className="oc-race-label">{owner ? '원카드!' : '잡기!'}</span>
        <span
          key={race.raceId}
          className="oc-race-timer"
          aria-hidden
          style={{
            ['--oc-race-start' as string]: String(start),
            animationDuration: `${remaining}ms`,
          }}
        />
      </button>
    </div>
  );
}
```

`client/src/features/onecard/OneCardMatchEnd.tsx`:

```tsx
import type { EndReason, OneCardMatchResult, StandingStatus } from '@/types/onecard';

const REASON_TEXT: Record<EndReason, string> = {
  FINISHED: '마지막 카드를 낸 사람이 나와 판이 끝났습니다.',
  LAST_STANDING: '한 명만 남아 판이 끝났습니다.',
  NO_HUMANS: '남은 사람이 없어 판이 끝났습니다.',
  STALEMATE: '더 진행할 수 없어 남은 장수로 순위를 매겼습니다.',
};

const STATUS_TEXT: Record<StandingStatus, string> = {
  FINISHED: '다 냄',
  ALIVE: '',
  BANKRUPT: '파산',
  DESERTED: '탈주',
};

interface Props {
  result: OneCardMatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
}

/**
 * 판 종료 — 순위(1, 1, 3 식)와 남은 장수·상태. 원카드는 한 판이 한 매치라 리매치 없이 이 화면에서 끝난다(방은
 * FINISHED — 게임판은 D-120 처럼 유지된다).
 */
export function OneCardMatchEnd({ result, mySeat, nameOf, onExit }: Props) {
  const standings = [...result.standings].sort((a, b) => a.rank - b.rank || a.seat - b.seat);
  const mine = standings.find((s) => s.seat === mySeat);
  const winners = standings.filter((s) => s.rank === 1).length;
  const title =
    mine?.rank === 1 ? (winners > 1 ? '공동 1위 🎉' : '승리 🎉') : '판 종료';

  return (
    <section className="match-end oc-match-end" aria-label="판 종료">
      <h3>{title}</h3>
      <p className="oc-match-reason">{REASON_TEXT[result.reason]}</p>
      <ol className="oc-standings">
        {standings.map((s) => (
          <li
            key={s.seat}
            className={[
              s.rank === 1 ? 'oc-standing-win' : '',
              s.seat === mySeat ? 'oc-standing-me' : '',
            ]
              .filter(Boolean)
              .join(' ')}
          >
            <span className="oc-standing-rank">{s.rank}위</span>
            <span className="oc-standing-name">
              {nameOf(s.seat)}
              {s.seat === mySeat ? ' (나)' : ''}
            </span>
            <span className="oc-standing-cards">{s.cardsLeft}장</span>
            {STATUS_TEXT[s.status] && (
              <span className="oc-standing-status">{STATUS_TEXT[s.status]}</span>
            )}
          </li>
        ))}
      </ol>
      {onExit && (
        <button type="button" className="oc-play" onClick={onExit}>
          메인으로
        </button>
      )}
    </section>
  );
}
```

- [ ] **Step 5: 구현 — 조립 루트**

`client/src/features/onecard/OneCardTable.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { useAuthStore } from '@/features/auth/authStore';
import { useStompRoom } from '@/ws/useStompRoom';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { seatMinWidth, viewOrder } from '@/components/seatOrder';
import { RoomChat } from '@/features/chat/RoomChat';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
import { cardKey } from '@/types/onecard';
import { onecardRoomSink } from './onecardRoomSink';
import { useOneCardStore, type PressAction } from './onecardStore';
import { isSuitChange } from './onecardRules';
import { ELIMINATED_LABEL, OneCardSeat, seatAccent } from './OneCardSeat';
import { OneCardCenter, raceResultText } from './OneCardCenter';
import { OneCardHand } from './OneCardHand';
import { OneCardMatchEnd } from './OneCardMatchEnd';
import { RaceButton } from './RaceButton';
import { ONE_CARD_TUTORIAL } from './tutorial/onecardTutorial';

/** `BUSY` 로 거절된 누름을 다시 보내기까지의 간격. 창(기본 3초) 안에 두 번 재시도할 여유가 있다. */
export const PRESS_RETRY_DELAY_MS = 120;
/** "늦었어요"를 보여 주는 시간. */
export const LATE_NOTICE_MS = 1500;

interface Props {
  roomId: string;
  playerIds: number[];
  myUserId: number;
  spectator?: boolean;
  botSeats?: number[];
  usernames?: Record<number, string>;
  turnSeconds?: number;
  spectatorCount?: number;
  onExit?: () => void;
  /** D-120 — 방은 FINISHED 인데 이 세션이 종료 전이를 봐서 게임판을 유지하는 중. 입력을 숨긴다. */
  roomFinished?: boolean;
}

/**
 * 원카드 게임판 조립 루트 (D-129). 스컬킹처럼 자기 소켓을 소유하고 자기 sink 를 주입한다(D-103) — 다른 게임의 코드
 * 경로는 실행되지 않는다. 좌석 수의 권위값은 `tableView.seats.length` 이고 `playerIds` 는 이름·아바타 조회용이다.
 *
 * <p>경쟁 창은 화면 위 오버레이 버튼(`RaceButton`)과 aria-live 알림으로 보여 준다. 락 경합 `BUSY` 를 받은 누름은
 * 창이 열린 동안 최대 2번 다시 보낸다(스토어가 신호를 올리고 여기서 보낸다).
 */
export function OneCardTable({
  roomId,
  playerIds,
  myUserId,
  spectator = false,
  botSeats = [],
  usernames = {},
  turnSeconds = 0,
  spectatorCount = 0,
  onExit,
  roomFinished = false,
}: Props) {
  const token = useAuthStore((s) => s.token);
  const { connected, sendAction, sendChat, chatPanelOpenRef } = useStompRoom(
    roomId,
    token,
    onecardRoomSink,
  );
  const [chatOpen, setChatOpen] = useState(false);
  const chatUnread = useRoomChatStore((s) => s.unreadCount);
  // 게임판에서는 수동으로만 연다 — 턴 타이머가 계속 흐른다(D-121).
  const rules = useTutorialGate(ONE_CARD_TUTORIAL.seenKey, false);

  const s = useOneCardStore();
  const seatCount = s.seats.length || playerIds.length;
  const mySeat = spectator ? -1 : s.mySeat;
  const opponents = viewOrder(seatCount, mySeat);
  const me = s.seats.find((x) => x.seat === mySeat) ?? null;
  const iAmOut = me?.eliminated != null;
  const raceOpen = s.race !== null;
  const myTurn =
    !spectator && mySeat >= 0 && !iAmOut && s.result === null && !raceOpen && s.turnSeat === mySeat;
  const canPress = !spectator && mySeat >= 0 && !iAmOut && s.result === null && !roomFinished;

  const nameOf = (seat: number) => {
    const uid = playerIds[seat];
    if (botSeats.includes(seat)) return usernames[uid] ?? `봇 ${seat}`;
    return usernames[uid] ?? `#${uid ?? seat}`;
  };

  // BUSY 재시도 — 스토어가 신호(retryNonce)를 올리면 기다리던 누름을 다시 보낸다.
  useEffect(() => {
    if (s.retryNonce === 0) return;
    const timer = window.setTimeout(() => {
      const press = useOneCardStore.getState().press;
      if (press) sendAction({ '@action': press.action, raceId: press.raceId });
    }, PRESS_RETRY_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [s.retryNonce, sendAction]);

  // "늦었어요"는 잠깐만.
  const clearRaceNotice = s.clearRaceNotice;
  useEffect(() => {
    if (s.raceNotice === null) return;
    const timer = window.setTimeout(clearRaceNotice, LATE_NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [s.raceNotice, clearRaceNotice]);

  const press = (action: PressAction) => {
    if (!s.race || s.press) return;
    s.startPress(s.race.raceId, action);
    sendAction({ '@action': action, raceId: s.race.raceId });
  };

  const playSelected = () => {
    const card = s.hand.find((c) => cardKey(c) === s.selectedKey);
    if (!card) return;
    if (isSuitChange(card) && s.suitChoice === null) return;
    sendAction({
      '@action': 'PLAY_CARD',
      card,
      ...(isSuitChange(card) ? { declaredSuit: s.suitChoice } : {}),
    });
  };

  const draw = () => sendAction({ '@action': 'DRAW' });

  // 진행 중 나가기는 되돌릴 수 없는 탈주다 — 탈락으로 최하위가 된다(§10). 관전자·종료 뒤·이미 탈락한 좌석은 묻지 않는다.
  const exit = () => {
    if (!onExit) return;
    if (
      !spectator &&
      s.result === null &&
      !roomFinished &&
      !iAmOut &&
      !window.confirm('게임 중에 나가면 탈주로 처리되어 최하위가 됩니다. 나가시겠습니까?')
    ) {
      return;
    }
    onExit();
  };

  const announcement = s.race
    ? `${nameOf(s.race.ownerSeat)} 카드 1장! ${
        canPress ? (s.race.ownerSeat === mySeat ? '원카드! 버튼을 누르세요' : '잡기! 버튼을 누르세요') : '경쟁 중'
      }`
    : s.lastRace
      ? raceResultText(s.lastRace, nameOf)
      : '';

  return (
    <div className="oc-table" style={{ ['--oc-seat-min' as string]: seatMinWidth(seatCount) }}>
      <header className="oc-header">
        <div className="oc-header-badges">
          <span className="oc-badge oc-badge-title">원카드</span>
          <span className="oc-badge">{seatCount}인</span>
          {turnSeconds > 0 && <span className="oc-badge">턴 {turnSeconds}초</span>}
          {spectatorCount > 0 && <span className="oc-badge">관전 {spectatorCount}</span>}
          {spectator && <span className="oc-badge">관전 모드</span>}
          <span className="oc-badge">{connected ? '● 연결' : '○ 끊김'}</span>
        </div>
        <div className="oc-header-badges">
          <button type="button" className="oc-badge" onClick={() => setChatOpen((v) => !v)}>
            채팅{chatUnread > 0 ? ` (${chatUnread})` : ''}
          </button>
          <button
            type="button"
            className="oc-badge"
            onClick={rules.show}
            title="게임 방법 다시 보기 — 턴 타이머는 계속 흐릅니다"
          >
            규칙
          </button>
          {onExit && (
            <button type="button" className="oc-badge" onClick={exit}>
              나가기
            </button>
          )}
        </div>
      </header>

      <ReconnectBanner connected={connected} />
      {s.errorMessage && (
        <p className="oc-error" role="alert">
          <span>{s.errorMessage}</span>
          <button type="button" className="oc-error-close" aria-label="닫기" onClick={() => s.setError(null)}>
            ×
          </button>
        </p>
      )}

      <div className="oc-seats">
        {opponents.map((seat) => {
          const view = s.seats.find((x) => x.seat === seat);
          if (!view) return null;
          return (
            <OneCardSeat
              key={seat}
              seat={view}
              userId={playerIds[seat]}
              username={usernames[playerIds[seat]]}
              isBot={botSeats.includes(seat)}
              isTurn={s.turnSeat === seat}
              isDisconnected={s.disconnectedSeats.has(seat)}
            />
          );
        })}
      </div>

      <OneCardCenter
        topCard={s.topCard}
        declaredSuit={s.declaredSuit}
        attackStack={s.attackStack}
        direction={s.direction}
        drawPileCount={s.drawPileCount}
        turnName={s.turnSeat >= 0 ? nameOf(s.turnSeat) : null}
        raceOpen={raceOpen}
        lastRace={s.lastRace}
        late={s.raceNotice === 'LATE'}
        nameOf={nameOf}
      />

      {s.result && (
        <OneCardMatchEnd result={s.result} mySeat={mySeat} nameOf={nameOf} onExit={onExit} />
      )}

      {/* D-120 — 방은 끝났는데 결과가 없다: 강제 종료이거나 MATCH_ENDED 직전의 찰나다. */}
      {roomFinished && !s.result && (
        <section className="oc-finished-note" aria-label="게임 종료">
          <p>게임이 종료되었습니다.</p>
          {onExit && (
            <button type="button" className="oc-play" onClick={exit}>
              메인으로
            </button>
          )}
        </section>
      )}

      {!spectator && mySeat >= 0 && (
        <>
          <div
            className={`oc-me${myTurn ? ' oc-me-turn' : ''}`}
            style={{ ['--oc-accent' as string]: seatAccent(mySeat) }}
          >
            <strong>{nameOf(mySeat)} (나)</strong>
            <span className="oc-me-count">손패 {s.hand.length}장</span>
            {myTurn && <span className="oc-badge oc-badge-turn">내 차례</span>}
            {myTurn && s.attackStack > 0 && (
              <span className="oc-badge oc-attack">공격받는 중 +{s.attackStack}</span>
            )}
            {me?.eliminated && (
              <span className="status-tag oc-seat-tag">탈락 — {ELIMINATED_LABEL[me.eliminated]}</span>
            )}
          </div>

          {!roomFinished && s.result === null && !iAmOut && (
            <OneCardHand
              hand={s.hand}
              selectedKey={s.selectedKey}
              suitChoice={s.suitChoice}
              topCard={s.topCard}
              declaredSuit={s.declaredSuit}
              attackStack={s.attackStack}
              myTurn={myTurn}
              raceOpen={raceOpen}
              onSelect={s.selectCard}
              onSuit={s.setSuitChoice}
              onPlay={playSelected}
              onDraw={draw}
            />
          )}
        </>
      )}

      {s.race && canPress && (
        <RaceButton race={s.race} mySeat={mySeat} pending={s.press !== null} onPress={press} />
      )}

      {/* 창이 열리고 닫힌 사실을 보조기기에 알린다 — 버튼에 자동 포커스를 주지 않는 대신이다. */}
      <div className="oc-sr-only" aria-live="assertive" aria-atomic="true">
        {announcement}
      </div>

      {chatOpen && (
        <RoomChat
          myUserId={myUserId}
          sendChat={sendChat}
          panelOpenRef={chatPanelOpenRef}
          onClose={() => setChatOpen(false)}
          roomId={roomId}
        />
      )}

      <TutorialDialog tutorial={ONE_CARD_TUTORIAL} open={rules.open} onClose={rules.close} />
    </div>
  );
}
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix client run test -- OneCardTable seatLayout SkullKingTable`
Expected: PASS — 원카드 게임판 27건, 스컬킹 `seatLayout` 20건·`SkullKingTable` 35건 그대로.

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  57 passed (57)` · `Tests  542 passed (542)`.

- [ ] **Step 7: 커밋**

```bash
git add client/src/components/seatOrder.ts \
  client/src/features/onecard/OneCardSeat.tsx \
  client/src/features/onecard/OneCardCenter.tsx \
  client/src/features/onecard/OneCardHand.tsx \
  client/src/features/onecard/RaceButton.tsx \
  client/src/features/onecard/OneCardMatchEnd.tsx \
  client/src/features/onecard/OneCardTable.tsx \
  client/src/features/onecard/OneCardTable.test.tsx \
  client/src/features/skullking/seatLayout.ts
git commit -m "feat(D-129): 원카드 게임판 — 좌석·가운데·손패·경쟁 버튼·종료 화면, 좌석 순서 공용화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: 클라 — 게임판 CSS

**Files:**
- Create: `client/src/styles/parts/19-onecard-table.css`, `client/src/styles/onecardCssNamespace.test.ts`
- Modify: `client/src/styles/cssSources.ts`, `client/src/styles/index.css`, `client/src/styles/parts/17-responsive.css`

**Interfaces:**
- Consumes: Task 4·5 의 클래스(`.oc-table`·`.oc-card*`·`.oc-race-*`·`.oc-practice-area`·`.oc-sr-only` …), 전역 색 토큰(`--color-*`).
- Produces: `onecardCssSource`(테스트 전용), `.oc-tokens`(튜토리얼 포털 토큰 — `.oc-table` 과 같은 값).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/styles/onecardCssNamespace.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { indexCssSource, onecardCssSource } from './cssSources';

/**
 * `.oc-` 네임스페이스 규칙을 기계로 강제한다 (D-129, 스컬킹 `skullkingCssNamespace.test` 와 같은 규칙).
 *
 * 원카드 게임판 CSS 가 공용 클래스(`.card-chip`, `.my-hand` …)를 재정의하면 다른 게임판이 조용히 번진다.
 * 캐스케이드 순서(19 가 18 뒤·17 앞)와 "19 에 폭 미디어 0개"도 함께 고정한다.
 */

/** 주석과 @keyframes 블록 제거본 — 키프레임의 `from`/`to` 는 선택자가 아니다. */
const cssCode = onecardCssSource
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');

/** 선언 블록 앞의 선택자 그룹만 뽑는다. */
function selectorGroups(source: string): string[] {
  const groups: string[] = [];
  const re = /(^|})\s*([^{}@]+?)\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(source)) !== null) {
    const sel = m[2].trim();
    if (sel) groups.push(sel);
  }
  return groups;
}

describe('19-onecard-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(40);
  });

  it('모든 선택자가 .oc- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.oc-')));
    expect(offenders, `.oc- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('공용 클래스를 단독으로 재정의하지 않는다', () => {
    const shared = [
      '.seat',
      '.card-chip',
      '.my-hand',
      '.action-bar',
      '.table-arena',
      '.hand-cards',
      '.match-end',
      '.status-tag',
    ];
    const offenders = groups.filter((g) =>
      g
        .split(',')
        .map((s) => s.trim())
        .some((one) => shared.includes(one)),
    );
    expect(offenders, `공용 클래스 단독 재정의: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('폭 미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });
});

/** 선택자가 정확히 `selector` 인 첫 블록의 `--토큰: 값` 을 뽑는다. */
function tokensOf(selector: string): Map<string, string> {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(cssCode);
  const tokens = new Map<string, string>();
  if (!m) return tokens;
  for (const [, name, value] of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    tokens.set(name, value.trim());
  }
  return tokens;
}

/**
 * 튜토리얼 다이얼로그는 body 포털이라 `.oc-table` 밖이다. 토큰이 안 풀리면 칩 글자색·조커 배경·연습 버튼 색이
 * 빠지는데 jsdom(css:false)은 이를 못 잡는다. 그래서 `.oc-tokens` 가 같은 값을 선언하는지 원문으로 고정한다.
 */
describe('19-onecard-table.css — .oc-tokens (튜토리얼 포털 토큰)', () => {
  const PORTAL_TOKENS = [
    '--oc-card-bg',
    '--oc-card-red',
    '--oc-card-black',
    '--oc-joker-black-bg',
    '--oc-joker-black-fg',
    '--oc-joker-color-bg',
    '--oc-joker-color-fg',
    '--oc-race-call',
    '--oc-race-catch',
  ];
  const table = tokensOf('.oc-table');
  const portal = tokensOf('.oc-tokens');

  it('칩과 연습 버튼이 쓰는 토큰을 모두 선언한다', () => {
    for (const t of PORTAL_TOKENS) expect(portal.get(t), t).toBeTruthy();
  });

  it('값이 게임판(.oc-table) 토큰과 같다', () => {
    expect(portal.size).toBeGreaterThan(0);
    for (const [name, value] of portal) {
      expect(value, name).toBe(table.get(name));
    }
  });

  it('레이아웃 속성은 갖지 않는다 — 토큰만 푸는 클래스다', () => {
    const block = /(^|})\s*\.oc-tokens\s*\{([^}]*)\}/.exec(cssCode)?.[2] ?? '';
    expect(block).not.toMatch(/(^|[;\s])(display|padding|min-height|gap)\s*:/);
  });
});

describe('index.css — 캐스케이드 순서', () => {
  const order = [...indexCssSource.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map((m) =>
    Number(m[1]),
  );

  it('19 가 18 뒤, 17 앞에 온다', () => {
    expect(order.indexOf(19)).toBeGreaterThan(order.indexOf(18));
    expect(order.indexOf(19)).toBeLessThan(order.indexOf(17));
  });

  it('17-responsive 가 여전히 마지막 part 다 (D-88 불변식)', () => {
    expect(order[order.length - 1]).toBe(17);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- onecardCssNamespace`
Expected: FAIL — `TypeError: Cannot read properties of undefined (reading 'replace')`(`onecardCssSource` 를 아직 내보내지 않아
모듈을 읽다 멈춘다), `Test Files  1 failed (1)`.

- [ ] **Step 3: 구현 — CSS part**

`client/src/styles/parts/19-onecard-table.css`:

```css
/* 원카드 게임판 (D-129) — 스컬킹 게임판(18)과 같은 규칙을 따른다.
 *
 * ⚠ 캐스케이드: index.css 에서 18 다음, **17-responsive 앞**에 import 된다. 그래서 이 파일에는 폭 미디어가
 *   **0개**다 — 좌석은 auto-fit 그리드라 브레이크포인트 없이 줄바꿈되고 손패는 여러 줄로 감긴다. 카드 축소는
 *   17 의 공용 규칙(.card-chip, .action-bar)을 상속한다. 폭·터치 규칙이 불가피하면 17-responsive.css **맨 끝**에
 *   .oc-* 블록만 추가한다(D-88).
 *
 * ⚠ 네임스페이스: 모든 선택자는 `.oc-` 클래스를 포함한다. 공용 클래스(.card-chip, .my-hand, .action-bar …)를
 *   단독으로 재정의하지 않는다 — 다른 게임판이 같은 클래스를 쓴다. (styles/onecardCssNamespace.test.ts 가 기계로 검사.)
 *
 * 테마: 중립색은 전역 토큰(--color-*)을 써서 라이트·다크가 저절로 따라간다. 카드 면은 두 테마 모두 밝은 종이색이다.
 */

/* 게임판은 `.app-shell` **밖**이라 tailwind 의 border-box 리셋이 닿지 않는다. 스코프 안에서만 명시한다. */
.oc-table,
.oc-table *,
.oc-table *::before,
.oc-table *::after {
  box-sizing: border-box;
}

/* ── 토큰 ─────────────────────────────────────────────────────────── */
.oc-table {
  --oc-seat-min: 132px;
  --oc-accent: #7c8ea0;
  --oc-card-bg: #fbfaf6;
  --oc-card-red: #c62f3a;
  --oc-card-black: #1f2228;
  --oc-joker-black-bg: #2b2d33;
  --oc-joker-black-fg: #f3f3f6;
  --oc-joker-color-bg: linear-gradient(135deg, #ef4f6b, #f3b63d 38%, #45b26b 66%, #4a86ee);
  --oc-joker-color-fg: #ffffff;
  --oc-race-call: #23955a;
  --oc-race-catch: #d9483d;
  --oc-accent-0: #5b8def;
  --oc-accent-1: #e85d75;
  --oc-accent-2: #3fa45b;
  --oc-accent-3: #e0b32a;
  --oc-accent-4: #7b5cd6;
  --oc-accent-5: #2fb5c0;

  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  min-height: 100vh;
}

/* ── 헤더 ─────────────────────────────────────────────────────────── */
.oc-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.oc-header-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.oc-badge {
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  font-size: 12px;
  white-space: nowrap;
}

button.oc-badge {
  cursor: pointer;
}

.oc-badge-title {
  font-weight: 700;
}

.oc-badge-turn {
  border-color: var(--oc-accent);
  font-weight: 700;
}

.oc-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(232, 93, 117, 0.18);
  border: 1px solid rgba(232, 93, 117, 0.5);
  font-size: 13px;
}

.oc-error-close {
  min-width: 32px;
  min-height: 32px;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 18px;
  cursor: pointer;
}

/* ── 상대 좌석 그리드 — 폭 미디어 없이 2~6인 대응 ─────────────────── */
/* max 는 1fr 이어야 auto-fit 이 min 기준으로 열을 센다(스컬킹 18 의 C5 실측과 같은 이유). */
.oc-seats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(var(--oc-seat-min), 1fr));
  justify-items: center;
  gap: 8px;
}

.oc-seat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  max-width: 168px;
  min-width: 0;
  padding: 6px 8px;
  border-radius: 8px;
  border: 1px solid var(--color-border);
  border-left: 4px solid var(--oc-accent);
  background: var(--color-bg-surface);
}

.oc-seat-turn {
  box-shadow: 0 0 0 2px var(--oc-accent);
}

.oc-seat-out {
  opacity: 0.5;
}

.oc-seat-offline {
  border-style: dashed;
}

.oc-seat-last {
  box-shadow: 0 0 0 2px var(--oc-race-catch);
}

.oc-seat-top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.oc-seat-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  background: var(--color-bg-surface-elevated);
  font-size: 16px;
}

.oc-seat-avatar img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.oc-seat-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}

.oc-seat-count {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.oc-seat-count-num {
  font-size: 22px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.oc-seat-count-unit {
  font-size: 12px;
  opacity: 0.75;
}

.oc-seat-one {
  margin-left: auto;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--oc-race-catch);
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
}

.oc-seat-tag {
  align-self: flex-start;
  font-size: 11px;
}

/* ── 가운데 — 더미·맨 위 카드·상태 ─────────────────────────────────── */
.oc-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
  background: var(--color-bg-surface-elevated);
}

.oc-piles {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
}

.oc-pile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.oc-pile-back {
  display: block;
  width: 62px;
  height: 88px;
  border-radius: 8px;
  border: 2px solid #555555;
  background: repeating-linear-gradient(45deg, #3b5ba9, #3b5ba9 6px, #2f4c92 6px, #2f4c92 12px);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4);
}

.oc-pile-count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.oc-top {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.oc-top-empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 88px;
  border-radius: 8px;
  border: 2px dashed var(--color-border);
}

.oc-center-info {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
}

.oc-attack {
  border-color: var(--oc-race-catch);
  background: rgba(217, 72, 61, 0.15);
  font-weight: 700;
}

.oc-declared {
  font-weight: 700;
}

.oc-race-status {
  border-color: var(--oc-race-call);
  font-weight: 700;
}

.oc-race-result,
.oc-race-late {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.oc-race-late {
  color: var(--oc-race-catch);
}

/* ── 카드 ─────────────────────────────────────────────────────────── */
.oc-card {
  gap: 0;
  border-color: rgba(0, 0, 0, 0.35);
  background: var(--oc-card-bg);
  font-weight: 700;
  cursor: default;
}

button.oc-card {
  cursor: pointer;
}

.oc-card-red {
  color: var(--oc-card-red);
}

.oc-card-black {
  color: var(--oc-card-black);
}

.oc-card-joker-black {
  background: var(--oc-joker-black-bg);
  color: var(--oc-joker-black-fg);
}

.oc-card-joker-color {
  background: var(--oc-joker-color-bg);
  color: var(--oc-joker-color-fg);
}

.oc-card-rank {
  font-size: 22px;
  line-height: 1.1;
}

.oc-card-suit {
  font-size: 22px;
  line-height: 1;
}

.oc-card-joker-mark {
  font-size: 18px;
  line-height: 1;
}

.oc-card-name {
  font-size: 10px;
  line-height: 1.2;
}

.oc-card-selected {
  outline: 3px solid #e0b32a;
  transform: translateY(-6px);
}

.oc-card-dimmed {
  opacity: 0.4;
}

.oc-card-compact {
  width: clamp(40px, 12vw, 52px);
  height: auto;
  aspect-ratio: 62 / 88;
}

/* ── 내 정보줄 / 손패 / 버튼 ─────────────────────────────────────── */
.oc-me {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 8px;
  border-left: 4px solid var(--oc-accent);
  background: var(--color-bg-surface);
}

.oc-me-turn {
  box-shadow: 0 0 0 2px var(--oc-accent);
}

.oc-me-count {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

/* 손패는 감긴다 — 원카드 손패는 20장 가까이 늘 수 있어 한 줄 겹치기(.overlap)를 쓰지 않는다. */
.oc-hand-cards {
  justify-content: center;
  min-height: 96px;
  padding-top: 8px;
}

.oc-hand-empty {
  font-size: 13px;
  opacity: 0.6;
}

.oc-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  /* 손패가 여러 줄로 감기면 마지막 줄이 버튼 줄에 붙는다(모바일 실측) — 띄운다. */
  margin-top: 8px;
}

.oc-play,
.oc-draw {
  min-height: 44px;
  padding: 10px 18px;
  border: none;
  border-radius: 8px;
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
}

.oc-play {
  background: #5b8def;
}

.oc-draw {
  background: #7a6bd0;
}

/* 반투명 흰색은 라이트 테마에서 흰 글자가 된다 — 불투명 회색으로 고정한다(스컬킹 C5 실측과 같은 이유). */
.oc-play:disabled,
.oc-draw:disabled {
  background: #8b90a6;
  color: #ffffff;
  cursor: default;
}

.oc-suits {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 8px;
  border: 1px dashed #e0b32a;
}

.oc-suit-opt {
  min-width: 44px;
  min-height: 44px;
  border-radius: 8px;
  border: 1px solid var(--color-border);
  background: var(--oc-card-bg);
  color: var(--oc-card-black);
  font-size: 22px;
  cursor: pointer;
}

.oc-suit-opt.oc-suit-red {
  color: var(--oc-card-red);
}

.oc-suit-opt.selected {
  outline: 3px solid #e0b32a;
}

/* ── 외치기 경쟁 버튼 (설계서 §4.10) ─────────────────────────────── */
/* 화면 위 고정 레이어 — 게임판이 스크롤돼도 버튼은 화면 안에 뜬다. 채팅 패널(100) 위, 모달 오버레이(200) 아래. */
.oc-race-layer {
  --oc-race-half-w: 64px;
  position: fixed;
  inset: 0;
  z-index: 150;
  pointer-events: none;
}

.oc-race-btn {
  position: absolute;
  transform: translate(-50%, -50%);
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 112px;
  min-height: 56px;
  padding: 8px 16px;
  overflow: hidden;
  border: 3px solid #ffffff;
  border-radius: 14px;
  color: #ffffff;
  font-size: 20px;
  font-weight: 800;
  pointer-events: auto;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.35);
}

.oc-race-call {
  background: var(--oc-race-call);
}

.oc-race-catch {
  background: var(--oc-race-catch);
}

.oc-race-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.oc-race-label {
  line-height: 1;
}

/* 창 끝까지 남은 시간 — 시작 비율(--oc-race-start)에서 0 으로 줄어든다. */
.oc-race-timer {
  display: block;
  width: 100%;
  height: 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.85);
  transform-origin: left center;
  animation-name: oc-race-shrink;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}

@keyframes oc-race-shrink {
  from {
    transform: scaleX(var(--oc-race-start, 1));
  }
  to {
    transform: scaleX(0);
  }
}

/* 튜토리얼 반응 연습 칸 — 다이얼로그 안이라 고정 레이어 대신 이 칸 안에 버튼을 띄운다. */
.oc-practice-area {
  position: relative;
  height: 200px;
  margin: 8px 0;
  overflow: hidden;
  border-radius: 10px;
  border: 1px dashed color-mix(in srgb, currentColor 30%, transparent);
}

/* ── 종료 ─────────────────────────────────────────────────────────── */
.oc-match-reason {
  margin: 4px 0;
  font-size: 13px;
}

.oc-standings {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0;
  padding: 0;
  list-style: none;
}

.oc-standings li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--color-bg-surface);
}

.oc-standings li.oc-standing-win {
  background: rgba(224, 179, 42, 0.22);
  font-weight: 700;
}

.oc-standings li.oc-standing-me {
  outline: 2px solid var(--oc-accent-0);
}

.oc-standing-rank {
  min-width: 2.5em;
  font-variant-numeric: tabular-nums;
}

.oc-standing-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.oc-standing-cards {
  font-variant-numeric: tabular-nums;
}

.oc-standing-status {
  font-size: 12px;
  opacity: 0.8;
}

.oc-finished-note {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, currentColor 18%, transparent);
}

.oc-finished-note p {
  margin: 0;
  font-weight: 700;
}

/* 화면에는 숨기고 보조기기에만 읽힌다(경쟁 알림). */
.oc-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ── 포털 토큰 — 튜토리얼 다이얼로그 ─────────────────────────────── */
/* 다이얼로그는 body 포털이라 .oc-table **밖**이다. 칩과 연습 버튼이 쓰는 토큰만 다시 푼다(GameTutorial.bodyClassName).
 * 값은 위 .oc-table 토큰과 같아야 한다 — onecardCssNamespace.test 가 대조한다. */
.oc-tokens {
  --oc-card-bg: #fbfaf6;
  --oc-card-red: #c62f3a;
  --oc-card-black: #1f2228;
  --oc-joker-black-bg: #2b2d33;
  --oc-joker-black-fg: #f3f3f6;
  --oc-joker-color-bg: linear-gradient(135deg, #ef4f6b, #f3b63d 38%, #45b26b 66%, #4a86ee);
  --oc-joker-color-fg: #ffffff;
  --oc-race-call: #23955a;
  --oc-race-catch: #d9483d;
}
```

- [ ] **Step 4: 구현 — 원문 읽기·import 순서·터치 하한**

`client/src/styles/cssSources.ts`:

```diff
 }
 
 export const skullkingCssSource = readCss('./parts/18-skullking-table.css');
+export const onecardCssSource = readCss('./parts/19-onecard-table.css');
 export const indexCssSource = readCss('./index.css');
```

`client/src/styles/index.css`:

```diff
  * 17 앞에 두는 이유: 17 이 마지막이라는 불변식을 유지하기 위함이고, 18 에 폭 규칙이
  * 없으므로 순서 때문에 사문화되는 규칙이 없다. */
 @import './parts/18-skullking-table.css';
+/* 원카드 게임판(D-129) — `.oc-` 접두 전용, 18 과 같은 규칙(공용 클래스 재정의 금지, 폭 미디어 0개). */
+@import './parts/19-onecard-table.css';
 /* ⚠ 폭 미디어 전량(720/768/480/440) + 터치 — 반드시 마지막 */
 @import './parts/17-responsive.css';
```

`client/src/styles/parts/17-responsive.css`:

```diff
   .sk-header button.sk-badge {
     min-width: 44px;
     min-height: 44px;
   }
 }
+
+/* D-129 — 원카드 헤더 버튼(채팅·규칙·나가기)도 44px 하한. 스컬킹 블록과 같은 이유다(알약 ~22px, 버튼 사이 6px).
+   터치 기기에서만 — 데스크톱 마우스 무영향. */
+@media (pointer: coarse) {
+  .oc-header button.oc-badge {
+    min-width: 44px;
+    min-height: 44px;
+  }
+}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix client run test -- CssNamespace`
Expected: PASS — 원카드 9건, 스컬킹 10건 그대로(19 를 18 과 17 사이에 넣어도 17 이 마지막이다).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  58 passed (58)` · `Tests  551 passed (551)`.

- [ ] **Step 6: 커밋**

```bash
git add client/src/styles/parts/19-onecard-table.css \
  client/src/styles/onecardCssNamespace.test.ts \
  client/src/styles/cssSources.ts \
  client/src/styles/index.css \
  client/src/styles/parts/17-responsive.css
git commit -m "feat(D-129): 원카드 게임판 CSS — .oc- 전용 part, 포털 토큰, 터치 하한

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: 클라 — `RoomPage` 분기와 게임판 유지 공용화

**Files:**
- Modify: `client/src/pages/RoomPage.tsx`, `client/src/pages/RoomPage.test.tsx`

**Interfaces:**
- Consumes: Task 5 `OneCardTable`(스컬킹 게임판과 같은 props).
- Produces: `RoomPage` 의 IN_GAME 분기 — `BOARD_HELD_GAMES`(`SKULL_KING`·`ONE_CARD`)는 이 세션에서 본 IN_GAME→FINISHED 직후에도 게임판을
  유지하고 `roomFinished` 를 넘긴다(D-120·D-122 좌석 얼리기 그대로). 그 밖의 게임은 기존 분기(티츄).

- [ ] **Step 1: 실패하는 테스트 작성**

`client/src/pages/RoomPage.test.tsx`:

```diff
 
 // 게임판은 소켓을 여는 무거운 컴포넌트라 props 만 잡는 스텁으로 바꾼다 (D-120). 텍스트는
 // 대기실 튜토리얼 테스트(D-121)가 IN_GAME 분기 진입을 확인하는 데 쓴다.
-const { skullProps, tichuProps } = vi.hoisted(() => ({
+const { skullProps, tichuProps, oneCardProps } = vi.hoisted(() => ({
   skullProps: [] as Record<string, unknown>[],
   tichuProps: [] as Record<string, unknown>[],
+  oneCardProps: [] as Record<string, unknown>[],
 }));
 vi.mock('@/features/skullking/SkullKingTable', () => ({
   SkullKingTable: (props: Record<string, unknown>) => {
     skullProps.push(props);
     return <div data-testid="skullking-table">스컬킹 게임판</div>;
+  },
+}));
+vi.mock('@/features/onecard/OneCardTable', () => ({
+  OneCardTable: (props: Record<string, unknown>) => {
+    oneCardProps.push(props);
+    return <div data-testid="onecard-table">원카드 게임판</div>;
   },
 }));
 vi.mock('@/features/tichu/GameTable', () => ({
```

```diff
     vi.clearAllMocks();
     skullProps.length = 0;
     tichuProps.length = 0;
+    oneCardProps.length = 0;
     names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
     useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
   });
```

```diff
     expect(screen.queryByTestId('skullking-table')).toBeNull();
   });
 
+  // D-129 — 원카드는 종료 화면(순위)을 가진 두 번째 게임이다. 이 분기가 없으면 원카드 방이 티츄 게임판으로 떨어진다.
+  it('원카드 게임 중이면 원카드 게임판을 그린다 — 티츄 게임판으로 떨어지지 않는다', async () => {
+    loadGame.mockResolvedValue(game('ONE_CARD', []));
+    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME', playerIds: [1, 2, 3] });
+
+    await screen.findByTestId('onecard-table');
+    expect(screen.queryByTestId('tichu-table')).toBeNull();
+    expect(oneCardProps.at(-1)!.playerIds).toEqual([1, 2, 3]);
+    expect(oneCardProps.at(-1)!.roomFinished).toBe(false);
+  });
+
+  it('원카드도 게임 중 FINISHED 를 받으면 게임판을 유지하고 roomFinished 를 넘긴다', async () => {
+    loadGame.mockResolvedValue(game('ONE_CARD', []));
+    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME' });
+    await screen.findByTestId('onecard-table');
+
+    act(() => {
+      metaCallback()({ ...ROOM, gameType: 'ONE_CARD', status: 'FINISHED' } as Room);
+    });
+
+    expect(screen.getByTestId('onecard-table')).toBeInTheDocument();
+    expect(oneCardProps.at(-1)!.roomFinished).toBe(true);
+  });
+
   it('티츄 방은 기존대로 FINISHED 전이 시 종료 카드로 바뀐다', async () => {
     loadGame.mockResolvedValue(game('TICHU', ['TARGET_SCORE', 'TEAMS', 'BETTING']));
     renderRoom({ gameType: 'TICHU', status: 'IN_GAME' });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix client run test -- RoomPage`
Expected: FAIL — `Tests  2 failed | 17 passed (19)`. 새 2건이 `onecard-table` 을 못 찾는다(원카드 방이 아직 티츄 게임판으로 떨어진다).

- [ ] **Step 3: 구현**

`client/src/pages/RoomPage.tsx`:

```diff
 import { useAuthStore } from '@/features/auth/authStore';
 import { GameTable } from '@/features/tichu/GameTable';
 import { SkullKingTable } from '@/features/skullking/SkullKingTable';
+import { OneCardTable } from '@/features/onecard/OneCardTable';
 import { TutorialDialog } from '@/features/tutorial/TutorialDialog';
 import { tutorialFor } from '@/features/tutorial/gameTutorials';
 import { useTutorialGate } from '@/features/tutorial/useTutorialGate';
```

```diff
   FINISHED: '종료',
 };
 
+/**
+ * D-120·D-129 — 종료 화면을 가진 게임. 이 세션이 IN_GAME→FINISHED 전이를 보면 게임판을 내리지 않고 결과를
+ * 보여 준다. 두 게임판은 같은 props 계약(`roomFinished` 포함)을 따른다.
+ */
+const BOARD_HELD_GAMES: ReadonlySet<string> = new Set(['SKULL_KING', 'ONE_CARD']);
+
 /** D-122 — 유지된 게임판이 쓰는 좌석 목록 스냅샷 (FINISHED 전이 직전 IN_GAME 메타). */
 interface HeldSeats {
   playerIds: number[];
```

```diff
   // IN_GAME — 게임판은 레거시 레이아웃이라 .app-shell 밖이다.
   // D-103: 게임 분기는 **이 한 곳**뿐이다. 각 게임판이 자기 소켓·sink 를 소유하므로
   // 다른 게임의 코드 경로는 실행조차 되지 않는다.
-  // D-120: 스컬킹은 이 세션에서 본 IN_GAME→FINISHED 직후에도 게임판을 유지한다(위
-  // boardHeld). 티츄는 아직 기존 동작 그대로다 — 매치 결과·'한 판 더' 게이팅이 먼저다.
+  // D-120: 종료 화면을 가진 게임(스컬킹·원카드)은 이 세션에서 본 IN_GAME→FINISHED 직후에도
+  // 게임판을 유지한다(위 boardHeld). 티츄는 아직 기존 동작 그대로다 — 매치 결과·'한 판 더' 게이팅이 먼저다.
   const roomFinished = room.status === 'FINISHED';
   if (
-    room.gameType === 'SKULL_KING' &&
+    BOARD_HELD_GAMES.has(room.gameType) &&
     (room.status === 'IN_GAME' || (boardHeld && roomFinished))
   ) {
     // D-122 — 유지된 게임판은 얼린 좌석 목록으로 그린다. 게임 중에는 라이브 메타 그대로.
```

```diff
       roomFinished && heldSeats
         ? heldSeats
         : { playerIds: room.playerIds, botSeats: room.botSeats ?? [] };
+    const board = {
+      roomId: room.roomId,
+      playerIds: seats.playerIds,
+      myUserId: user.userId,
+      spectator: iAmSpectator,
+      botSeats: seats.botSeats,
+      usernames,
+      turnSeconds: room.turnSeconds ?? 0,
+      spectatorCount: (room.spectatorIds ?? []).length,
+      onExit: handleLeave,
+      roomFinished,
+    };
     return (
       <main className="room-page">
-        <SkullKingTable
-          roomId={room.roomId}
-          playerIds={seats.playerIds}
-          myUserId={user.userId}
-          spectator={iAmSpectator}
-          botSeats={seats.botSeats}
-          usernames={usernames}
-          turnSeconds={room.turnSeconds ?? 0}
-          spectatorCount={(room.spectatorIds ?? []).length}
-          onExit={handleLeave}
-          roomFinished={roomFinished}
-        />
+        {room.gameType === 'ONE_CARD' ? <OneCardTable {...board} /> : <SkullKingTable {...board} />}
       </main>
     );
   }
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix client run test -- RoomPage gameTutorials`
Expected: PASS — `RoomPage` 19건, `gameTutorials` 11건(페이지가 게임 튜토리얼 폴더를 직접 import 하지 않는다).

Run: `npm --prefix client run build:check` 뒤 `npm --prefix client run test`
Expected: 타입 오류 없음, 마지막 두 줄 `Test Files  58 passed (58)` · `Tests  553 passed (553)`.

- [ ] **Step 5: 커밋**

```bash
git add client/src/pages/RoomPage.tsx \
  client/src/pages/RoomPage.test.tsx
git commit -m "feat(D-129): RoomPage 에 원카드 게임판 — 종료 화면을 가진 게임의 게임판 유지 공용화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: 문서·명령·수치 + Phase Gate

코드는 바꾸지 않는다. 아래 diff 는 Task 1~7 을 마친 뒤의 파일 기준이다(이 문서들은 앞 태스크가 건드리지 않았다).

**Files:**
- Modify: `docs/plans/onecard.md`(§4.3·§4.5b·§4.10·§6 — D-129 반영, §7 S4 후속), `docs/stomp-protocol.md`(원카드 순번·슬롯·큐 카탈로그),
  `docs/rules-onecard.md`(클라 S4 매핑), `docs/qa-scenarios.md`(원카드 게임판 확인 절), `CLAUDE.md`(게임판 유지·토큰·CSS·순번·명령)
- Modify: `docs/implementation-status.md`, `README.md`, `docs/case-study-multi-game.md`, `docs/plans/mvp-roadmap.md`(수치)

**Interfaces:**
- Consumes: Task 1~7 의 파일·테스트 이름과 실측 수치.

- [ ] **Step 1: 설계·프로토콜·룰·QA 문서**

`docs/plans/onecard.md`:

```diff
   소비해, 그 이벤트를 못 받는 다른 클라에게는 다음 공개 이벤트가 구멍(gap)으로 보이고 resync 를
   부른다. 원카드는 내거나 먹을 때마다 비공개 `HAND_UPDATED` 가 나가므로 이대로면 매 차례 전원이
   resync 한다. *(D-128: S3 에서는 만들지 않았다 — D-126 병합 뒤 원카드 비공개 이벤트에 `sequenced()` 재정의만
-  더한다(S4 착수 조건, §4.5b). 그 전까지 원카드 비공개 이벤트도 방 순번을 쓴다.)*
+  더한다(S4 착수 조건, §4.5b). 그 전까지 원카드 비공개 이벤트도 방 순번을 쓴다.)* *(D-129: S4 에서 재정의했다 — 원카드
+  비공개 이벤트는 순번을 쓰지 않는다.)*
 - **비공개 payload 는 손패 전체 + `handVersion`**(상태 버전이라 좌석마다 단조 증가). 클라는 가진 것보다 낮은
   버전을 버린다 — 순번이 없어도 resync 스냅샷(비공개 뷰에도 `handVersion`)과 순서가 뒤바뀌어
   손패가 되돌아가지 않는다.
```

````diff
 
 **S3 에서 만들지 않았다(D-128, 사용자 결정)** — 같은 확장을 D-126(티츄 resync)이 넣는다. D-126 이 병합되면 원카드
 비공개 이벤트 2종에 `sequenced()` 를 false 로 재정의만 더한다(S4 착수 조건). 그 전까지 원카드 비공개 이벤트도
-방 순번을 쓴다. 아래는 원래 설계다.
+방 순번을 쓴다. 아래는 원래 설계다. **D-129(S4)에서 재정의했다** — D-126 이 넣은 기본 메서드를 원카드 손패 이벤트 2종이
+false 로 바꾼다(`OneCardEvent.sequenced()` = `!isPrivate()`).
 
 ```java
 /** false 면 envelope 에 seq 를 붙이지 않는다(순번을 소비하지 않음). 기본 true — 기존 게임 동작 그대로. */
````

```diff
 
 ### 4.10 클라이언트
 
+**구현됨(S4, D-129)** — 아래 설계대로 붙었고, 달라진 점은 *기울임* 메모로 단다.
+
 - `features/onecard/`: `onecardStore`(순수 리듀서) · `onecardRoomSink`(모듈 상수) · `OneCardTable` ·
   `RaceButton` · 좌석·더미·손패 컴포넌트 · `types/onecard.ts`.
 - `RoomPage` 의 IN_GAME 분기에 원카드를 추가하고, 종료 뒤 게임판 유지(D-120 `boardHeld`)를 스컬킹
```

```diff
   게임판은 `.app-shell` 밖이라 box-sizing 을 스코프에서 명시한다.
 - **경쟁 버튼**: 게임판 위 오버레이 레이어. 슬롯 N개를 게임판 기준 % 좌표로 정의하고(손패·내
   좌석·더미 영역 회피) 서버 지터를 반경 안에서 적용한다. 최소 44×44px. 주인은 "원카드!",
-  나머지는 "잡기!". 관전자에게는 보이지 않는다.
+  나머지는 "잡기!". 관전자에게는 보이지 않는다. *(D-129: 좌표는 화면(뷰포트) 기준으로 바꿨다 — 게임판은 모바일에서
+  스크롤되므로 게임판 기준이면 버튼이 화면 밖에 뜰 수 있다. 슬롯 표는 `features/onecard/raceSlots.ts`, 버튼 안 막대가 창 끝까지
+  남은 시간이다. 탈락자에게도 보이지 않는다.)*
 - **접근성**: 실제 `<button>` + aria-live 안내. **자동 포커스와 전역 단축키는 두지 않는다.** 둘 다
   사실상 "위치와 무관하게 즉시 누르기"라 무작위 위치의 의미와 레이팅 공정성을 깬다. 고정 위치
   보조 모드는 비레이팅 방 한정으로 후속 검토한다.
```

```diff
 | S1 | 룰 명세 `docs/rules-onecard.md`(§3.2 확정) | — | 없음(문서) | 사용자 검토 |
 | S2 | 순수 엔진 + 불변식 + 시뮬레이션 | S1 | 서버 신규 패키지 | 룰 단위 테스트, 2~6인 시뮬레이션 전부 종료, 54장 보존 |
 | S3 | 포트 확장 1건(엔진 타이머 — 비순번은 D-126 에 맡김) + 어댑터 + 봇 + 기록기(V12) + 라운드 시작 | S2 | 서버(인프라 1건) | 경쟁 통합 테스트 5종, 봇 풀매치 IT, 인프라 grep 0건, 티츄·스컬킹 회귀 |
-| S4 | 클라 게임판 + 경쟁 버튼 + 튜토리얼, `AVAILABLE` 전환 | S0·S3 | 클라(+정의 1줄) | Vitest, 브라우저 실측(데스크톱·모바일) |
+| S4 | 클라 게임판 + 경쟁 버튼 + 튜토리얼 + 비공개 이벤트 비순번 — `AVAILABLE` 전환은 D-116 병합 뒤 별건(D-129) | S0·S3·D-126 | 클라(+서버 재정의 1건) | Vitest, 브라우저 실측(데스크톱·모바일) |
 | S5 | 통합 리뷰 → 문서 수치 → 배포 | S4 | — | `check.sh` 전체, 운영 스모크 |
 
 - S0 과 S1 은 서로 독립이라 병행할 수 있다(worktree 분리). S2 는 신규 패키지라 S0 과 병행 가능.
 - S3 병합 때는 정의를 `COMING_SOON` 으로 둔다. 서버만 배포돼도 방을 만들 수 없다(`RoomService` 가
-  AVAILABLE 만 허용). S4 병합에서 `AVAILABLE` 로 바꾼다.
+  AVAILABLE 만 허용). S4 병합에서 `AVAILABLE` 로 바꾼다. *(D-129: 전환은 S4 와 분리했다 — D-116 병합 뒤 기본값 한 줄과 운영
+  문서를 바꾸는 별건이다.)*
 - 단계마다 Phase Gate(변경 요약 + 다음 진입 동의).
 - D 번호는 착수 시점의 다음 번호를 쓴다(D-116 은 다른 세션이 사용 중).
 
```

```diff
     분기, 인터페이스의 실제 기본 메서드(`timer`/`onTimer` → empty) 실행.
 - **운영**
   - `MIRBOARD_ONECARD_STATUS` 를 `docs/deploy.md`·`.env.example` 에 적는다. **S4 전에는 AVAILABLE 로 켜지 않는다** — 현 클라는
-    ONE_CARD 방을 기본 분기(티츄 게임판)로 그린다. S4 에서 코드 기본값 전환을 클라와 같은 배포로 한다.
+    ONE_CARD 방을 기본 분기(티츄 게임판)로 그린다. S4 에서 코드 기본값 전환을 클라와 같은 배포로 한다. *(D-129: 클라는 S4 로
+    준비됐고, 전환은 D-116 병합 뒤 별건으로 뺐다.)*
   - D-116·D-126 이 이 브랜치와 같은 문서(CLAUDE.md·README·케이스 스터디·decisions·status·roadmap·redis-keys)와 테스트 수를
     건드린다 — 병합 뒤 인용 수치를 케이스 스터디 §부록 명령으로 다시 잰다. 또 운영은 `MIRBOARD_MESSAGING_GATEWAY=redis` 라 D-116
     전에는 머신이 2대 이상일 때 `GameStartingEvent` 가 재발행돼 라운드 시작이 인스턴스마다 반복된다(티츄·스컬킹과 같은 기존
```

```diff
   `implementation-status.md` §14 포트 설명)는 S5 문서 정리에서, 케이스 스터디 부록 (2) 의 기존 어긋남("6파일 11행" 등)도 S5 재측정에서.
 - **S2 잔여**: 검사기 기존 분기의 직접 테스트·시뮬레이션 오라클 보강, 그리고 core `GameEvent.isPrivate()` 가 Jackson 에
   `"private"` 키로 직렬화되는 문제(원카드는 자체 차단, 스컬킹·티츄 payload 에는 남아 있다).
+
+### S4 후속 (D-129)
+
+S4 브라우저 실측(데스크톱·모바일, 라이트·다크)에서 본 것 중 이번에 고치지 않은 것이다.
+
+- **경쟁 창마다 봇 루프 WARN** — 창이 열리면 `pendingSeats` 가 비어 봇 루프가 `Bot loop: no pending bot action`(WARN)을 남긴다.
+  운영 로그 소음이라 그 경우는 DEBUG 로 낮춘다(인프라, 게임 중립 — 차례 없는 상태는 정상일 수 있다).
+- **카드 내기 연타** — `카드 내기` 를 빠르게 두 번 누르면 두 번째가 서버에서 거절된다(`NOT_YOUR_TURN` 등, 스컬킹도 같다). 보낸
+  뒤 다음 상태가 올 때까지 버튼을 잠그는 게임판 공용 처리를 검토한다.
+- **번들 크기** — 메인 번들이 566kB(원카드 +27kB, 500kB 경고는 그 전부터)다. 게임판을 `RoomPage` 에서 지연 로딩(`lazy`)해
+  게임별로 나누는 것을 검토한다.
+
```

`docs/stomp-protocol.md`:

```diff
 | `HAND_DEALT` | `{ seat, cards, phaseCardCount: 8\|14 }` | 8장(Dealing 진입) 또는 14장(전환 후) 손패 스냅샷, 카드를 낸 뒤 남은 손패(D-62). seq: null (D-126) |
 | `CARDS_RECEIVED` | `{ seat, received: [{ card, fromSeat }] }` | 패스로 받은 3장 + 출처 (스왑 직후). seq: null (D-126) |
 | `ERROR` | `{ code, message }` | 본인의 잘못된 액션 (seq: null) |
+| `HAND_UPDATED` | `{ seat, hand, received, handVersion }` | 원카드 — 손패 전체(아래 원카드 절). seq: null (D-129) |
 
 > **D-126 — 본인 큐 배달.** 클라는 늘 `/user/queue/room/{id}` 를 구독하고, Spring 이 이를 본인 세션 목적지
 > `/queue/room/{id}-user{세션}` 으로 바꾼다. 그래서 브로커 prefix 는 `/queue` 여야 한다(예전 `/user/queue` 설정에서는
```

```diff
 ## 원카드 (gameType=ONE_CARD, D-128)
 
 같은 목적지·같은 envelope 를 쓴다. 좌석은 **0 ~ seatCount−1** (2~6). 룰 정본은 `docs/rules-onecard.md`.
-클라 게임판(S4) 전까지 카탈로그 상태는 `COMING_SOON` 이다(`mirboard.onecard.status`).
+클라 게임판(S4, D-129)은 준비됐고, 카탈로그 상태는 열림 전환(별건) 전까지 `COMING_SOON` 이다(`mirboard.onecard.status`).
 
 **클라 → 서버 `@action`**:
 
```

```diff
 `OWNER_CANNOT_CATCH`.
 
 **경쟁 창(§9)**:
-- `slot` 은 `0..7` — 슬롯 8개는 클라가 게임판 기준 좌표로 정의하는 **프로토콜 상수**다. `jitterX`·`jitterY` 는
+- `slot` 은 `0..7` — 슬롯 8개는 클라가 화면(뷰포트) 기준 좌표로 정의하는 **프로토콜 상수**다(`client/src/features/onecard/raceSlots.ts`, D-129). `jitterX`·`jitterY` 는
   `−100..100`(슬롯 반경의 백분율). 창 길이 기본 3000ms(`mirboard.onecard.race-window-millis`).
 - 창이 열린 동안 `PLAY_CARD`·`DRAW` 는 `RACE_IN_PROGRESS`. `raceId` 가 지금 창과 다르면(이미 닫힘 포함) `NO_RACE`
   — 클라는 "늦었어요" 정도로 보여 주면 된다. 서버가 먼저 처리한 누름이 이기고, 창 끝·봇 시각 직후에 처리된
```

```diff
 `{ raceId, ownerSeat, slot, jitterX, jitterY, windowMillis, remainingMillis }`(창 끝까지 남은 시간 — 봇 시각 아님),
 `result` 는 `MATCH_ENDED` 와 같은 모양. `privateHand` = `{ seat, hand, handVersion }`.
 
-> **순번**: 지금은 비공개 `HAND_*` 도 방 순번(`seq`)을 쓴다 — 받지 않는 좌석에는 구멍으로 보여 resync 를 부른다.
-> D-126 이 `GameEvent.sequenced()` 를 넣었다 — 원카드 비공개 이벤트를 `false` 로 재정의하는 것은 S4 다.
+> **순번**: 비공개 `HAND_*` 는 방 순번(`seq`)을 쓰지 않는다(D-129 — D-126 의 `GameEvent.sequenced()` 를 false 로 재정의).
+> 받지 않는 좌석에 구멍을 만들지 않으며, 손패의 순서는 `handVersion` 이 지킨다.
 
 ---
 
```

`docs/rules-onecard.md`:

```diff
 `server/src/{main,test}/java/com/mirboard/domain/game/onecard/` 기준이고, 위치는 줄 번호 대신 메서드 이름으로
 적는다(줄 번호는 코드가 바뀌면 바로 틀린다). 포트 어댑터·저장·봇 정책·기록은 S3(D-128)에서 붙였다 — 서버 경로로
 확인한 것은 각 절의 **코드(S3):** / **테스트(S3):** 다(인프라 테스트는 `server/src/test/java/com/mirboard/infra/` 기준).
+클라 게임판(S4, D-129)이 룰을 비추는 곳은 **코드(S4):** / **테스트(S4):** 이고 경로는 `client/src/features/onecard/` 기준이다 —
+클라는 판정하지 않으며 표시만 미러한다(권위는 서버).
 
 > **출처와 하우스 룰.** 원카드는 모임마다 규칙이 크게 다르다. 이 문서는 D-123 에서 고른 **표준 기본형**만
 > 다룬다. 원문이 답하지 않거나 갈리는 항목을 우리가 정한 것은 본문에 **[결정]** 으로 표시하고 §12 에 모았다.
```

```diff
 - **코드:** `rules/PlayRules.canPlay`(§5.2·§5.3), 기준 무늬 `state/OneCardState.baseSuit`, 내기 처리
   `OneCardEngine.play`(§5.4 순서 — 효과, 0장이면 종료, 1장이면 경쟁, 다음 차례)
 - **테스트:** `rules/PlayRulesTest`(9건), `OneCardEnginePlayTest`(7 의 무늬 지정, 거절 사유)
+- **코드(S4):** `onecardRules.canPlay`(§5.2·§5.3·§6.2 의 미러 — 내 차례에 낼 수 없는 카드를 흐리게, 표시 전용), 7 의 무늬
+  고르기는 `OneCardHand`
+- **테스트(S4):** `onecardRules.test`(§5·§6.2 표), `tutorial/PlayQuiz.test`(퀴즈 정답을 `canPlay` 와 대조)
 
 ## 6. 공격과 반격
 
```

```diff
   `mirboard.onecard.*` 설정(`OneCardGameDefinition`).
 - **테스트(S3):** `bot/OneCardRaceIT`(6건 — 주인이 먼저 `CALLED`, 사람이 잡음 `CAUGHT`, 창 안에 반응한 봇이 잡음,
   아무도 안 누르면 엔진 타이머가 `EXPIRED` 로 닫음, 창 중 탈주 `CANCELLED`, 닫힌 창의 누름 거절)
+- **코드(S4):** 경쟁 버튼 `RaceButton`(슬롯 표 `raceSlots.ts` — 화면 기준 8자리 + 지터, 주인 "원카드!"·나머지 "잡기!",
+  관전자·탈락자에게는 없음, 자동 포커스·단축키 없음, aria-live 알림), 누름 거절 처리 `onecardStore.notePressRejected`(`BUSY` 는
+  창이 열린 동안 2번까지 다시 보내기, `NO_RACE` 는 "늦었어요")
+- **테스트(S4):** `OneCardTable.test`(경쟁 8건), `onecardStore.test`(누름 거절), `raceSlots.test`(슬롯·지터 범위)
 
 ## 10. 파산과 탈락
 
```

`docs/qa-scenarios.md`:

```diff
 복귀 → `useStompRoom` 이 `/resync` 호출 → 게임 상태 (TableView + 본인 손패) 즉시
 복원. 다른 플레이어 상태는 변하지 않음.
 
+## 원카드 게임판 확인 (D-129)
+
+카탈로그 열림 전환 전에도 로컬에서는 설정으로 켜서 볼 수 있다 — `MIRBOARD_ONECARD_STATUS=AVAILABLE ./gradlew :server:bootRun`.
+경쟁 버튼을 눈으로 보려면 창과 봇 반응을 늘린다 — 예를 들어 로컬 `server/config/application.yml`(커밋하지 않는다)에
+`mirboard.onecard.race-window-millis: 20000` 과 `bot-reaction-{owner,catcher}-{min,max}-millis` 18000~19000 을 둔다.
+
+1. 미르보드카페 → 새 방 만들기 → 원카드, 4인, 빈 좌석 봇으로 채우기 → 준비. 처음 들어간 대기실에서 원카드 튜토리얼(12단계)이
+   한 번 자동으로 뜬다 — 2단계 카드 그림(♥ 빨강, ♠ 검정, 두 조커), 11단계 퀴즈, 12단계 반응 연습 버튼이 칸 안에 뜨는지 본다.
+2. 게임판: 상대 좌석은 이름·장수만, 가운데에 뽑을 더미 장수·맨 위 카드(7 이면 지정 무늬)·진행 방향·차례가 보인다. 내 차례에
+   낼 수 없는 카드가 흐리다(맨 위와 무늬·숫자가 다르면). 7 을 고르면 무늬 버튼 4개가 뜨고 고르기 전에는 낼 수 없다.
+3. 공격을 받으면 "공격받는 중 +N"·"먹기 (N장)". 반격 못 하는 카드를 내면 오류 줄에 문구가 뜨고 × 로 닫힌다.
+4. 누군가 1장이 되면 화면 위쪽 무작위 자리에 버튼이 뜬다(주인 "원카드!" 초록, 나머지 "잡기!" 빨강, 안에 남은 시간 막대).
+   눌러서 "잡기 성공: 나 → 상대 벌칙 1장"이 가운데에 뜨는지, 상대 장수가 1 늘었는지 본다. 그 동안 내기·먹기 버튼은 막힌다.
+5. 판이 끝나면 순위 표(1, 1, 3 식 · 남은 장수 · 다 냄/파산/탈주)가 뜨고 방이 끝나도 게임판이 남는다. '메인으로'는 탈주 확인 없이 나간다.
+6. 모바일 폭(375px)과 라이트 테마에서 가로 스크롤 없이 손패가 여러 줄로 감기고, 헤더 버튼이 44px 인지 본다.
+7. 브라우저 개발자 도구 네트워크: 카드를 내거나 먹어도 `/resync` 가 다시 불리지 않는다(입장·재연결 때만, D-129).
+
 ## 분산 시연 (멀티 인스턴스, Phase 6D)
 
 기본은 단일 인스턴스 + `mirboard.messaging.gateway=in-memory`. 멀티 인스턴스에서
```

- [ ] **Step 2: `CLAUDE.md`**

`CLAUDE.md`:

```diff
 - **클라 인게임도 게임 중립 (D-103)**: `useStompRoom` 은 게임 스토어를 import 하지 않고
   `RoomEventSink` 를 주입받는다(게임별 sink 파일이 스토어에 꽂는다). sink 는 **모듈 상수**여야
   하고 각 메서드는 **호출 시점에 `getState()`** 를 읽어야 한다(훅이 sink 를 ref 로 잡아
-  effect deps 에서 빼기 때문). 게임판 분기는 `RoomPage` 의 IN_GAME 한 곳뿐이고(스컬킹은 이
-  세션에서 본 IN_GAME→FINISHED 직후에도 게임판을 유지하고 `roomFinished` 를 넘긴다, D-120), 각
+  effect deps 에서 빼기 때문). 게임판 분기는 `RoomPage` 의 IN_GAME 한 곳뿐이고(스컬킹·원카드는 이
+  세션에서 본 IN_GAME→FINISHED 직후에도 게임판을 유지하고 `roomFinished` 를 넘긴다, D-120·D-129), 각
   게임판이 자기 소켓·sink 를 소유해 다른 게임의 코드 경로는 실행되지 않는다. 공개 이벤트의 순번
   판정(중복·구멍)은 훅만 한다(D-124) — 게임 스토어는 `lastSeq` 없는 순수 리듀서다.
 - **튜토리얼도 게임이 선언한다 (D-121)**: 각 게임이 `features/{game}/tutorial` 에
```

```diff
   `features/tutorial/gameTutorials.ts` 한 곳뿐이다. 허브·대기실은 `tutorialFor(gameId)` 만 본다
   (게임 튜토리얼 폴더 직접 import 금지 — `gameTutorials.test` 가 원문 검사). 자동 노출은 그 게임
   대기실 첫 입장 1회, 게임판은 수동만(타이머가 흐른다). 다이얼로그는 body 포털이라 게임판 스코프
-  밖이므로 게임 토큰은 `bodyClassName` 으로 푼다(스컬킹 `.sk-tokens`).
+  밖이므로 게임 토큰은 `bodyClassName` 으로 푼다(스컬킹 `.sk-tokens`, 원카드 `.oc-tokens`).
 - **시작된 게임의 좌석은 불변 (D-122)**: 좌석 인덱스가 STOMP 좌석 판정·비공개 이벤트 라우팅·resync 의
   기준이라, 진행 중 매치에서 탈주로 처리되지 않은 leave 는 no-op, FINISHED 방 leave 는 좌석 목록을
   건드리지 않는다. 매치를 액션 경로 밖에서 끝내는 쪽(탈주 MATCH_ENDED·강제 종료)은 방 액션 락 안에서
```

```diff
 | Build     | Gradle 9.4.1 (wrapper), Kotlin DSL                                                                   |
 | Auth      | JWT HS256 12h, BCrypt. 시크릿은 `MIRBOARD_JWT_SECRET` 환경변수                                     |
 | Migration | **Flyway** — JPA `ddl-auto` 사용 금지                                                          |
-| Frontend  | Vite + React 18 + TypeScript, `@stomp/stompjs` + SockJS, `@dnd-kit`, Zustand. **Phase 20(D-76)**: Tailwind v3(`preflight:false`)+shadcn/ui(slate, CSS vars), 라이트/다크 토글(`themeStore`, `<html>.dark`, 기본 dark). shadcn 화면은 `.app-shell` 로 감싼다(스코프 base). 게임판 기하는 `styles/parts/*` 유지(D-94 분할). **게임별 게임판 CSS 는 신규 part + 접두 네임스페이스**(스컬킹 `.sk-`, D-103) — 공용 클래스 재정의 금지, `17-responsive.css` 는 계속 마지막. 게임판은 `.app-shell` 밖이라 tailwind border-box 리셋이 안 닿으니 스코프에서 명시할 것 |
+| Frontend  | Vite + React 18 + TypeScript, `@stomp/stompjs` + SockJS, `@dnd-kit`, Zustand. **Phase 20(D-76)**: Tailwind v3(`preflight:false`)+shadcn/ui(slate, CSS vars), 라이트/다크 토글(`themeStore`, `<html>.dark`, 기본 dark). shadcn 화면은 `.app-shell` 로 감싼다(스코프 base). 게임판 기하는 `styles/parts/*` 유지(D-94 분할). **게임별 게임판 CSS 는 신규 part + 접두 네임스페이스**(스컬킹 `.sk-` D-103, 원카드 `.oc-` D-129) — 공용 클래스 재정의 금지, `17-responsive.css` 는 계속 마지막. 게임판은 `.app-shell` 밖이라 tailwind border-box 리셋이 안 닿으니 스코프에서 명시할 것 |
 | Data      | PostgreSQL 16 (영속, Phase 7-1 부터 D-39), Redis 7 (실시간 세션/방 상태)                                        |
 | Test      | JUnit 5 + Mockito + Testcontainers / Vitest + RTL                                          |
 
```

````diff
 # 단위 테스트 (Vitest + jsdom)
 npm --prefix client run test
 npm --prefix client run test -- authStore   # 특정 테스트만
+npm --prefix client run test -- onecard     # 원카드 게임판·스토어·튜토리얼 (D-129)
 ```
 
 ## STOMP envelope 규약 (자주 참조됨)
````

```diff
 
 **비공개 이벤트는 순번을 쓰지 않는다 (D-126)** — `GameEvent.sequenced()`(기본 true)를 false 로.
 `seq` 는 클라가 공개 토픽에서 구멍을 찾는 기준이라, 비공개 이벤트가 쓰면 다른 클라에게 다음 공개
-이벤트가 항상 구멍이 된다(티츄는 카드를 낼 때마다 전원 resync 했다). 티츄는 `!isPrivate()`, 스컬킹은
+이벤트가 항상 구멍이 된다(티츄는 카드를 낼 때마다 전원 resync 했다). 티츄·원카드(D-129)는 `!isPrivate()`, 스컬킹은
 아직 true. 공개 상태가 바뀌면 **반드시 공개 이벤트로** 알릴 것 — resync 가 우연히 고쳐 주는 것에 기대지
 말 것(소원 해제가 그랬다 → `WISH_CLEARED`). resync 는 방 액션 락 안에서 상태·`eventSeq` 를 함께 읽는다.
 
 - 서버 → 클라 공개 `/topic/room/{roomId}`
   - 티츄: `PLAYED`, `PASSED`, `TURN_CHANGED`, `TRICK_TAKEN`, `TICHU_DECLARED`, `WISH_MADE`, `WISH_CLEARED`(D-126), `ROUND_ENDED`, `MATCH_ENDED` 등
   - 스컬킹(D-102): `BIDDING_STARTED`, `BID_SUBMITTED`(값 없음), `BIDS_REVEALED`, `PLAYING_STARTED`, `CARD_PLAYED`, `TURN_CHANGED`, `TRICK_TAKEN`, `ROUND_ENDED`, `SEAT_DESERTED`, `MATCH_ENDED`
-  - 원카드(D-128, 클라 S4 전 COMING_SOON): `MATCH_STARTED`(타입만 정의 — 시작 때 발행하지 않는다, 시작 상태는 resync 로), `CARD_PLAYED`, `CARDS_DRAWN`(장수만), `PILE_RESHUFFLED`, `TURN_CHANGED`, `RACE_OPENED`, `RACE_RESOLVED`, `PLAYER_ELIMINATED`, `MATCH_ENDED` — payload 는 결과값
+  - 원카드(D-128·D-129, 열림 전환 전 COMING_SOON): `MATCH_STARTED`(타입만 정의 — 시작 때 발행하지 않는다, 시작 상태는 resync 로), `CARD_PLAYED`, `CARDS_DRAWN`(장수만), `PILE_RESHUFFLED`, `TURN_CHANGED`, `RACE_OPENED`, `RACE_RESOLVED`, `PLAYER_ELIMINATED`, `MATCH_ENDED` — payload 는 결과값
 - 서버 → 클라 비공개 `/user/queue/room/{roomId}`
   - 티츄: `HAND_DEALT`, `CARDS_RECEIVED`, `ERROR`
   - 스컬킹: `HAND_DEALT`, `ERROR`
-  - 원카드: `HAND_UPDATED`(손패 전체 + `handVersion`), `ERROR` — `HAND_DEALT` 는 타입만 정의돼 시작 때 보내지 않는다(시작 손패는 resync 의 `privateHand`)
+  - 원카드: `HAND_UPDATED`(손패 전체 + `handVersion`, 순번 없음 — D-129), `ERROR` — `HAND_DEALT` 는 타입만 정의돼 시작 때 보내지 않는다(시작 손패는 resync 의 `privateHand`)
 - 클라 → 서버 `/app/room/{roomId}/action`
   - 티츄: `DECLARE_GRAND_TICHU`, `DECLARE_TICHU`, `READY`, `PASS_CARDS`, `PLAY_CARD`(마작 포함 시 `wishRank` 동봉, D-109), `PASS_TRICK`, `GIVE_DRAGON_TRICK`
   - 스컬킹: `PLACE_BID`, `PLAY_CARD`(티그리스는 `declaredAs`)
```

- [ ] **Step 3: 수치 실측**

Run: `npm --prefix client run test`
Expected: 마지막 두 줄 `Test Files  58 passed (58)` · `Tests  553 passed (553)`.

Run: `./scripts/check.sh server` (Docker 필요, 약 5분 — 이 스크립트가 OrbStack/Colima 소켓을 잡아 준다) 뒤 집계:

```bash
python3 - <<'EOF'
import glob, re, xml.etree.ElementTree as ET
t = s = f = d = oc = ocu = 0
for p in glob.glob('server/build/test-results/test/*.xml'):
    r = ET.parse(p).getroot(); n = int(r.get('tests')); name = r.get('name')
    t += n; s += int(r.get('skipped')); f += int(r.get('failures')) + int(r.get('errors'))
    if not re.search(r'(IT|IntegrationTest)$', name): d += n
    if name.startswith('com.mirboard.domain.game.onecard'):
        oc += n
        if name.endswith('Test'): ocu += n
print(f"tests={t} skipped={s} failed={f} dockerfree={d} ({d/t:.0%}) onecardDomain={oc} onecardUnit={ocu}")
EOF
```

Expected: `tests=1238 skipped=5 failed=0 dockerfree=1039 (84%) onecardDomain=182 onecardUnit=178`. 기준은 main `cce26fb` 의 1236건
(Docker 불필요 1037건)이고 이 계획이 서버 테스트 2건(Docker 불필요)을 더한다. main 이 그사이 바뀌었다면 바뀐 값에 증가분을 더해
Step 4 의 문서를 쓴다.

Run: `grep -rniE 'onecard|one_card|원카드' server/src/main/java/com/mirboard/infra`
Expected: 출력 없음.

- [ ] **Step 4: 수치 반영**

`docs/implementation-status.md`:

```diff
 | 4 | WebSocket/STOMP 실시간 | ✅ | `infra.ws`, `infra.config.WebSocketConfig` |
 | 5 | 티츄 룰 엔진 (전 페이즈 + 특수 카드) | ✅ | `domain.game.tichu` |
 | 5b | 스컬킹 (룰 엔진 + 배선 + 클라 게임판) | ✅ | `domain.game.skullking`, `features/skullking` (§16) |
-| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 — 클라 게임판은 S4, 그때까지 COMING_SOON) | 🟡 | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `docs/rules-onecard.md` (D-127·D-128) |
+| 5c | 원카드 (룰 엔진 + 서버 배선·봇·기록 + 클라 게임판·경쟁 버튼·튜토리얼 — 카탈로그 열림 전환만 남음, 그때까지 COMING_SOON) | 🟡 | `domain.game.onecard`, `infra.bot.EngineTimerScheduler`, `client/src/features/onecard`, `docs/rules-onecard.md` (D-127·D-128·D-129) |
 | 6 | 봇 플레이어 (빈 좌석 자동 채움) | ✅ | `infra.bot`, `domain.game.tichu.bot` |
 | 7 | 재접속 동기화 (resync) | ✅ | `RoomService`, `GET /rooms/{id}/resync` |
 | 8 | 탈주/끊김 처리 (유예→패널티) | ✅ | `infra.ws` 탈주 핸들러, `DesertionService` |
```

```diff
 
 ## 13. 테스트 현황
 
-- **서버**: **1236건** (D-126 병합 시점 실측, 실패 0, 그중 Docker 불필요 1037건, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 180건(그중 Docker 불필요 176건) + 원카드 서버 경로 IT 10건·엔진 타이머 단위 15건. 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
+- **서버**: **1238건** (D-129 시점 실측, 실패 0, 그중 Docker 불필요 1039건, 대형 봇 평가 5건은 `MIRBOARD_BOT_EVAL=1` 전용이라 skip). 스컬킹 도메인 375건(그중 Docker 불필요 371건), 원카드 도메인 182건(그중 Docker 불필요 178건) + 원카드 서버 경로 IT 10건·엔진 타이머 단위 15건. 테스트 JVM 힙 1g · 컨텍스트 캐시 상한 4(IT 가 늘어 기본 512m 에서 OOM — D-122 검증 중 발견).
   단위(룰 엔진·족보·ELO·JWT·카탈로그·포트 어댑터) + 통합(Testcontainers PostgreSQL 16/
   Redis — auth/rooms/STOMP/봇/동시성/매치 영속/2-인스턴스 인계).
 - 룰·봇 단위는 **Docker 불필요** — `./scripts/check.sh rules` 에 묶여 있다(티츄·스컬킹·원카드 룰 + 세 봇
   평가, ~20s). 스컬킹·원카드 매치 기록 IT(D-115·D-128)는 Docker 가 필요해 `rules` 에서 뺐다.
-- **클라이언트**: **432건 / 48파일** (D-126 병합 시점 실측 — 카드 비행 오버레이 수정 포함, 실패 0). Vitest + RTL — 스토어
+- **클라이언트**: **553건 / 58파일** (D-129 시점 실측 — 원카드 게임판·스토어·튜토리얼 121건 포함, 실패 0). Vitest + RTL — 스토어
   리듀서, 족보 타입, 카드 에셋 매핑 등.
 - 통합 테스트는 Docker 필요. 실행 명령은 `CLAUDE.md` "자주 쓰는 명령" 참조.
 - **밀폐성(D-113)**: IT 는 Testcontainers 로 자기 Postgres/Redis 를 띄우고 compose 에 기대지
```

`README.md`:

```diff
 [![Deploy](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml/badge.svg)](https://github.com/cykimh/mirboard/actions/workflows/deploy.yml)
 
 Spring Boot 4 / Java 25 · PostgreSQL · Redis · React + TypeScript ·
-서버 테스트 1236건 / 클라 432건
+서버 테스트 1238건 / 클라 553건
 
 **라이브**: https://mirboard.fly.dev — 로그인 화면 「게스트로 바로 체험하기」로 가입 없이 들어갈 수 있습니다.
 유휴 시 머신이 멈춰 첫 접속에 약 30초 걸립니다(콜드 스타트).
```

`docs/case-study-multi-game.md`:

```diff
 `SkullKingInvariantChecker` 를 통과 케이스뿐 아니라 **고의로 위반시킨 상태 8건**
 (+ 오탐 방지 통과 2건)으로 검출 능력 자체를 테스트했습니다.
 
-**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1236건 중 1037건(84%)이 Docker 불필요**합니다.
+**여기 한 줄만 숫자를 씁니다** — 서버 테스트 **1238건 중 1039건(84%)이 Docker 불필요**합니다.
 자랑이 아니라 §2 의 2계층 분리가 값을 냈다는 인과 증거입니다.
 
 `코드:` `skullking/trick/TrickResolver.java` · `skullking/invariant/SkullKingInvariantChecker.java` ·
```

`docs/plans/mvp-roadmap.md`:

```diff
 | M4 | G | 쇼케이스 마감: README 리뉴얼·데모 GIF·케이스 스터디·데모 계정·라이브 배포·CD | ✅ D-105: README·케이스 스터디·스크린샷·데모 계정 시더·CD 워크플로. **라이브 배포 2026-10-03**(https://mirboard.fly.dev) — 첫 재배포에서 5월의 Upstash Redis 소멸을 발견해 Fly 자체 Redis 로 교체(D-114), `FLY_API_TOKEN` 등록으로 main 푸시 = 자동 배포. GIF 는 정적 스크린샷으로 대체 |
 | M5 | E | 멀티게임: 포트 졸업 → 디스패치 seam 포트화 → 스컬킹(2~8인) | ✅ S0~S6 완료(D-97~D-104): 포트·인원 가변·룰 명세·순수 엔진(305건)·탈주(유령 좌석)·인게임 배선(봇 풀매치 IT)·클라 게임판(Row-Flow, 실측 완료). 실행 단위 `docs/plans/multi-game-sessions.md`. **잔여 별건**: 스컬킹 매치 영속·ELO(D-02 게임별 rating 분리 선행), 끊김 유예 구간 정지(D-104 한계), 요트/할리갈리 |
 | M6 | E·A | 스컬킹 완성도: ① 게임별 전적·레이팅(매치 영속·개인전 ELO·게임별 랭킹) ② 라운드 점수표 ③ 봇 휴리스틱 ④ 튜토리얼 | ✅ ① D-115 게임별 전적·레이팅 · ② D-120 라운드 결과(다음 라운드 예측 중 비차단)·점수표·종료 후 게임판 유지 · ③ D-119 봇 휴리스틱(공개 정보 뷰 + `TrickResolver` 승률) · ④ D-121 게임별 튜토리얼 레지스트리 + 스컬킹 13단계·퀴즈 (2026-10-03). 후속: 봇 상대 모델링(8인 대 최약수 대등), 티츄 봇 방 종료 화면 유지 |
-| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건) · **S3 완료**(D-128 포트 엔진 타이머 + 어댑터·휴리스틱 봇·기록 V12, 경쟁 창 IT, 서버 1219건 — 클라 전까지 COMING_SOON). 다음: S4 클라(착수 조건: D-126 병합 뒤 비공개 이벤트 sequenced=false) → S5 통합·배포 |
+| M7 | E | 세 번째 게임 원카드: 표준 기본형 룰 + "원카드!/잡기!" 실시간 경쟁(무작위 위치 버튼, 봇 반응 시간) + 포트 엔진 타이머 확장 | 🟡 설계 승인(D-123, `docs/plans/onecard.md`) · **S0 완료**(D-124 순번 판정을 훅으로) · **S1 완료**(D-125 `docs/rules-onecard.md`) · **S2 완료**(D-127 순수 엔진, 테스트 134건) · **S3 완료**(D-128 포트 엔진 타이머 + 어댑터·휴리스틱 봇·기록 V12, 경쟁 창 IT, 서버 1219건 — 클라 전까지 COMING_SOON). **S4 완료**(D-129 클라 게임판·경쟁 버튼·튜토리얼 + 비공개 이벤트 비순번, 클라 553건). 다음: 카탈로그 열림 전환(D-116 병합 뒤 별건) → S5 통합·배포 |
 
 **M0 상세(완료)**: D-83(`SecurityConfig`/`WebSocketConfig` origin 화이트리스트+헤더),
 D-84(`LoginAttemptService`·`AuthRateLimiter`·`rate_limit_fixed_window.lua`, 전부 Redis 휘발 —
```

- [ ] **Step 5: 커밋**

```bash
git add docs/plans/onecard.md \
  docs/stomp-protocol.md \
  docs/rules-onecard.md \
  docs/qa-scenarios.md \
  CLAUDE.md \
  docs/implementation-status.md \
  README.md \
  docs/case-study-multi-game.md \
  docs/plans/mvp-roadmap.md
git commit -m "docs(D-129): 원카드 S4 — 설계·프로토콜·룰 매핑·QA 절·테스트 수치

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: 브라우저 확인과 Phase Gate (컨트롤러)**

구현 에이전트가 아니라 컨트롤러가 한다. `docs/qa-scenarios.md` 의 "원카드 게임판 확인 (D-129)" 절을 따라 확인한다 — 로컬에서
`mirboard.onecard.status: AVAILABLE`(커밋하지 않는 `server/config/application.yml` 덮어쓰기)로 켜고, 개발용 DB 를 다른 세션과 나눠 쓰지
않도록 일회용 Postgres·Redis 컨테이너를 다른 포트로 띄운다. 데스크톱·모바일(375px)과 라이트·다크에서 게임판·경쟁 버튼 잡기·
종료 화면·튜토리얼을 보고, 끝나면 서버·클라·컨테이너를 모두 내린다. 그다음 `git log --oneline main..HEAD` 로 태스크 커밋 8개(리뷰 수정
커밋이 있으면 그만큼 더)를 확인하고 사용자에게 보고한다 — 만든 것, 실측 수치, 다음 단계(카탈로그 열림 전환 — D-116 병합 뒤 별건,
`MIRBOARD_ONECARD_STATUS` 운영 문서화와 함께). **사용자 승인 전에는 main 에 병합하지 않는다.** main 에 푸시하면 자동 배포되지만
원카드는 계속 "준비 중"이다(방 생성 불가).
