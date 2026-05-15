package com.mirboard.domain.game.tichu.invariant;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 10D — 티츄 상태의 cross-cutting invariant 검증.
 *
 * <p>본 체커는 **테스트 전용 호출** — `TichuGameStateStore.save` 의 정상 흐름에는
 * 통합하지 않는다 (프로덕션 런타임 비용 0). 단, ApplicationContext bean 으로 등록
 * 가능하면 testHook 등록 시점에 쓸 수 있게 pure function 으로 작성.
 *
 * <p>Invariant 목록:
 * <ol>
 *   <li><b>카드 보존</b> — 모든 플레이어의 hand + tricksWon + 진행 중 트릭의
 *       accumulatedCards = 56 (전체 덱), 카드 중복 없음 (모든 카드 unique).</li>
 *   <li><b>finishedOrder 유일성</b> — finishedOrder &gt; 0 값들이 unique
 *       (중복된 1위 없음).</li>
 *   <li><b>턴 단조성</b> — Playing 상태의 currentTurnSeat 는 active player
 *       (finishedOrder == -1). 단, Dragon trick 이 pending 인 경우 currentTopSeat
 *       이 finished 일 수 있음 (D-52 예외).</li>
 *   <li><b>활성 wish rank 범위</b> — 2..14.</li>
 * </ol>
 *
 * <p>점수 보존 (cardPointsSum == 100) 은 `ScoreCalculator` 가 이미 보장하므로
 * 본 checker 에선 다루지 않는다 — `ScoreCalculatorTest.all_card_points_sum_is_one_hundred` 참고.
 */
public final class TichuInvariantChecker {

    private TichuInvariantChecker() {
    }

    /** 깨진 invariant 발견 시 IllegalStateException 던짐. */
    public static void check(TichuState state) {
        if (state instanceof TichuState.Dealing d) checkDealing(d);
        else if (state instanceof TichuState.Passing p) checkPassing(p);
        else if (state instanceof TichuState.Playing pl) checkPlaying(pl);
        else if (state instanceof TichuState.RoundEnd re) checkRoundEnd(re);
    }

    private static void checkDealing(TichuState.Dealing d) {
        // Dealing(8): hand 8장 + reserved 6장 = 14. 4 좌석 합 = 56.
        // Dealing(14): hand 14장 (reserved 비어 있음). 4 좌석 합 = 56.
        List<Card> all = new ArrayList<>();
        for (PlayerState p : d.players()) {
            all.addAll(p.hand());
            all.addAll(p.tricksWon());
        }
        for (List<Card> reserved : d.reservedSecondHalf().values()) {
            all.addAll(reserved);
        }
        assertDeckIntact(all, "Dealing");
    }

    private static void checkPassing(TichuState.Passing p) {
        List<Card> all = new ArrayList<>();
        for (PlayerState ps : p.players()) {
            all.addAll(ps.hand());
            all.addAll(ps.tricksWon());
        }
        assertDeckIntact(all, "Passing");
    }

    private static void checkPlaying(TichuState.Playing pl) {
        // 카드 보존: hand + tricksWon + currentTrick.accumulatedCards = 56
        List<Card> all = new ArrayList<>();
        for (PlayerState p : pl.players()) {
            all.addAll(p.hand());
            all.addAll(p.tricksWon());
        }
        all.addAll(pl.trick().accumulatedCards());
        assertDeckIntact(all, "Playing");

        // finishedOrder 유일성
        assertFinishedOrderUnique(pl.players());

        // 턴 단조성 (Dragon pending 예외)
        TrickState t = pl.trick();
        boolean dragonPending = t.currentTop() != null
                && t.currentTop().cards().size() == 1
                && t.currentTop().cards().get(0).is(com.mirboard.domain.game.tichu.card.Special.DRAGON);
        if (!dragonPending) {
            PlayerState current = pl.players().get(t.currentTurnSeat());
            if (current.isFinished()) {
                throw new IllegalStateException(
                        "Invariant violation: currentTurnSeat=" + t.currentTurnSeat()
                                + " is finished (finishedOrder=" + current.finishedOrder() + ")");
            }
        }

        // 활성 wish rank 범위
        if (t.activeWish() != null) {
            int r = t.activeWish().rank();
            if (r < 2 || r > 14) {
                throw new IllegalStateException("Invariant violation: wish rank out of [2,14]: " + r);
            }
        }
    }

    private static void checkRoundEnd(TichuState.RoundEnd re) {
        // 카드 보존: hand 잔여 (4등) + 모든 tricksWon = 56
        List<Card> all = new ArrayList<>();
        for (PlayerState p : re.players()) {
            all.addAll(p.hand());
            all.addAll(p.tricksWon());
        }
        assertDeckIntact(all, "RoundEnd");

        // finishedOrder 유일성
        assertFinishedOrderUnique(re.players());
    }

    private static void assertDeckIntact(List<Card> cards, String context) {
        if (cards.size() != Deck.SIZE) {
            throw new IllegalStateException(
                    "Invariant violation (" + context + "): card count = " + cards.size()
                            + ", expected " + Deck.SIZE);
        }
        Set<Card> uniq = new HashSet<>(cards);
        if (uniq.size() != Deck.SIZE) {
            throw new IllegalStateException(
                    "Invariant violation (" + context + "): " + (Deck.SIZE - uniq.size())
                            + " duplicate cards across players/tricks");
        }
    }

    private static void assertFinishedOrderUnique(List<PlayerState> players) {
        Set<Integer> seen = new HashSet<>();
        for (PlayerState p : players) {
            if (p.finishedOrder() <= 0) continue;
            if (!seen.add(p.finishedOrder())) {
                throw new IllegalStateException(
                        "Invariant violation: duplicate finishedOrder=" + p.finishedOrder()
                                + " among players");
            }
            if (p.finishedOrder() > 4) {
                throw new IllegalStateException(
                        "Invariant violation: finishedOrder=" + p.finishedOrder()
                                + " > 4 (must be in [1,4])");
            }
        }
    }
}
