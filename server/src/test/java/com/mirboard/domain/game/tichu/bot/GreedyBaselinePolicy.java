package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandDetector;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.Comparator;
import java.util.List;

/**
 * D-118 테스트 전용 두 번째 기준선. "아무 전략 없이 손패를 빨리 털기만 하는" 봇이다.
 * 휴리스틱이 랜덤만 이기는 게 아니라 <b>휴리스틱 고유의 판단</b>(파트너·선언·폭탄·소원·
 * 패스)으로 이기는지를 보려고 둔다.
 *
 * <ul>
 *   <li>리드: {@link LegalActionEnumerator#enumerateFull} 후보 중 최다 장수 → 최저 랭크</li>
 *   <li>팔로우: 이기는 최저 비폭탄, 없으면 패스 — 파트너가 top 이어도 이긴다</li>
 *   <li>폭탄·선언·소원 없음, 실제 랭크 최저 3장 패스(봉황 14.5·용 16), 용 트릭은 첫 상대(좌석
 *       오름차순)에게</li>
 * </ul>
 * 결정적이다.
 */
final class GreedyBaselinePolicy {

    private GreedyBaselinePolicy() {
    }

    static TichuAction choose(TichuState state, int seat) {
        List<TichuAction> legal = LegalActionEnumerator.enumerateFull(state, seat);
        if (legal.isEmpty()) return null;

        TichuAction give = legal.stream()
                .filter(a -> a instanceof TichuAction.GiveDragonTrick)
                .min(Comparator.comparingInt(a -> ((TichuAction.GiveDragonTrick) a).toSeat()))
                .orElse(null);
        if (give != null) return give;

        return switch (state) {
            case TichuState.Dealing __ -> legal.stream()
                    .filter(a -> a instanceof TichuAction.Ready).findFirst().orElse(null);
            case TichuState.Passing p -> lowestThree(p.players().get(seat).hand());
            case TichuState.Playing pl -> pl.trick().currentTurnSeat() != seat ? null
                    : play(legal, pl.trick().isLead());
            case TichuState.RoundEnd __ -> null;
        };
    }

    /**
     * 실제 랭크 최저 3장. 정규 비트 순서(개=0, 봉황=1, 마작=2, …)는 랭크 순서가 아니라서 그대로
     * 앞 3장을 고르면 봉황을 매번 넘긴다 — 랭크 키로 다시 정렬한다(같은 랭크는 정규 순서 유지).
     */
    private static TichuAction lowestThree(List<Card> hand) {
        if (hand.size() < 3) return null;
        List<Card> cards = HandPlanner.cards(HandPlanner.mask(hand)).stream()
                .sorted(Comparator.comparingInt(GreedyBaselinePolicy::rankKey))
                .toList();
        return new TichuAction.PassCards(cards.get(0), cards.get(1), cards.get(2));
    }

    /** 랭크 ×2: 개 0, 마작 2, 일반 rank·2, 봉황 29(14.5), 용 32(16). */
    private static int rankKey(Card c) {
        if (c.special() == null) return c.rank() * 2;
        return switch (c.special()) {
            case DOG -> 0;
            case MAHJONG -> 2;
            case PHOENIX -> 29;
            case DRAGON -> 32;
        };
    }

    private record Play(long mask, Hand hand) {
    }

    private static TichuAction play(List<TichuAction> legal, boolean lead) {
        List<Play> plays = legal.stream()
                .filter(a -> a instanceof TichuAction.PlayCard)
                .map(a -> HandPlanner.mask(((TichuAction.PlayCard) a).cards()))
                .distinct()
                .sorted(HandPlanner::compareCanonical)
                .map(m -> new Play(m, HandDetector.detect(HandPlanner.cards(m)).orElseThrow()))
                .toList();
        List<Play> nonBomb = plays.stream().filter(p -> !p.hand().isBomb()).toList();
        Play choice;
        if (lead) {
            Comparator<Play> order = Comparator.<Play>comparingInt(p -> -Long.bitCount(p.mask()))
                    .thenComparingInt(p -> p.hand().rank());
            choice = (nonBomb.isEmpty() ? plays : nonBomb).stream().min(order).orElse(null);
        } else {
            choice = nonBomb.stream()
                    .min(Comparator.comparingInt(p -> strength(p.hand())))
                    .orElse(null);
            if (choice == null) return new TichuAction.PassTrick();
        }
        return choice == null ? null : new TichuAction.PlayCard(HandPlanner.cards(choice.mask()));
    }

    /** 팔로우 세기: 봉황 단독은 이전 top 위 0.5 라 같은 rank 일반 카드보다 비싸게 친다. */
    private static int strength(Hand h) {
        return h.phoenixSingle() ? 29 : h.rank() * 2;
    }
}
