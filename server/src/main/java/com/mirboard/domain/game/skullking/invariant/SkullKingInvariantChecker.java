package com.mirboard.domain.game.skullking.invariant;

import com.mirboard.domain.game.skullking.Dealer;
import com.mirboard.domain.game.skullking.bid.BidRules;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 스컬킹 상태의 cross-cutting invariant 검증 (티츄 {@code TichuInvariantChecker} 대응).
 *
 * <p>본 체커는 <b>테스트 전용 호출</b>이다 — 정상 저장 경로에 넣지 않는다 (프로덕션 런타임
 * 비용 0). pure function 이라 어디서든 부를 수 있다.
 *
 * <p><b>기준이 덱 전체(70)가 아니라 {@code handSize × seatCount} 인 것이 요점이다 (D-101).</b>
 * 8인 라운드 9·10 은 70장 중 6장을 쓰지 않으므로 (§4) 덱 전체로 잡으면 그 두 라운드에서
 * 거짓 위반이 난다.
 *
 * <p>중복 검사는 Set 이 아니라 <b>multiset</b> 이다 — 덱에 같은 해적이 5장 있어 Set 크기로는
 * 보존을 확인할 수 없다. 대신 종류별 출현 횟수가 덱 허용치를 넘지 않는지 본다. 색상 카드는
 * 덱에 1장씩뿐이라 이 검사만으로 색상 카드 복제가 잡히고, 특수 카드끼리의 뒤바뀜은 애초에
 * 구별 불가능한 상태라 위반이 아니다.
 *
 * <p>Invariant 목록:
 * <ol>
 *   <li><b>카드 보존</b> — 손패 + 획득 트릭 + 진행 중 트릭 = {@code handSize × seatCount}</li>
 *   <li><b>덱 허용치</b> — 어떤 카드도 덱에 든 장수보다 많이 존재하지 않는다</li>
 *   <li><b>좌석 순서</b> — {@code players.get(i).seat() == i}</li>
 *   <li><b>예측 범위</b> — 제출된 예측은 {@code 0 ~ handSize}</li>
 *   <li><b>턴 유효성</b> — Playing 의 현재 차례는 좌석 범위 안이거나 -1(트릭 완성 과도기)</li>
 * </ol>
 */
public final class SkullKingInvariantChecker {

    private SkullKingInvariantChecker() {
    }

    /** 깨진 invariant 발견 시 {@link IllegalStateException}. */
    public static void check(SkullKingState state) {
        int seatCount = state.seatCount();
        int handSize = Dealer.handSize(state.roundNumber(), seatCount);

        assertSeatOrder(state.players());
        assertBidsInRange(state, handSize);

        List<SkullCard> all = collectCards(state);
        assertCardCount(all, handSize * seatCount, state.phaseName());
        assertWithinDeckAllowance(all, state.phaseName());

        if (state instanceof SkullKingState.Playing playing) {
            assertTurnValid(playing);
        }
    }

    /** 이 상태에 존재하는 모든 카드 — 어디에 있든 한 번씩. */
    private static List<SkullCard> collectCards(SkullKingState state) {
        List<SkullCard> all = new ArrayList<>();
        for (PlayerState player : state.players()) {
            all.addAll(player.hand());
            for (TrickResult trick : player.tricksWon()) {
                for (PlayedCard pc : trick.cards()) {
                    all.add(pc.card());
                }
            }
        }
        if (state instanceof SkullKingState.Playing playing) {
            for (PlayedCard pc : playing.trick().played()) {
                all.add(pc.card());
            }
        }
        return all;
    }

    private static void assertCardCount(List<SkullCard> cards, int expected, String phase) {
        if (cards.size() != expected) {
            throw new IllegalStateException(
                    "Invariant violation (" + phase + "): card count = " + cards.size()
                            + ", expected " + expected);
        }
    }

    private static void assertWithinDeckAllowance(List<SkullCard> cards, String phase) {
        Map<SkullCard, Integer> counts = new HashMap<>();
        for (SkullCard card : cards) {
            counts.merge(card, 1, Integer::sum);
        }
        counts.forEach((card, count) -> {
            int allowed = allowanceOf(card);
            if (count > allowed) {
                throw new IllegalStateException(
                        "Invariant violation (" + phase + "): " + card + " appears " + count
                                + " times but the deck holds only " + allowed);
            }
        });
    }

    private static int allowanceOf(SkullCard card) {
        if (card.isSuit()) {
            return 1;
        }
        SpecialKind kind = card.special();
        return kind.countInDeck();
    }

    private static void assertSeatOrder(List<PlayerState> players) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).seat() != i) {
                throw new IllegalStateException(
                        "Invariant violation: players[" + i + "].seat = " + players.get(i).seat());
            }
        }
    }

    private static void assertBidsInRange(SkullKingState state, int handSize) {
        for (PlayerState player : state.players()) {
            if (!player.hasBid()) {
                continue;
            }
            if (!BidRules.isValid(player.bid(), handSize)) {
                throw new IllegalStateException(
                        "Invariant violation: seat " + player.seat() + " bid " + player.bid()
                                + " out of [0, " + handSize + "]");
            }
        }
    }

    private static void assertTurnValid(SkullKingState.Playing playing) {
        int turn = playing.currentTurnSeat();
        if (turn == -1) {
            return;
        }
        if (turn < 0 || turn >= playing.seatCount()) {
            throw new IllegalStateException(
                    "Invariant violation: currentTurnSeat = " + turn
                            + " for " + playing.seatCount() + " seats");
        }
        if (playing.players().get(turn).hand().isEmpty()) {
            throw new IllegalStateException(
                    "Invariant violation: seat " + turn + " is to play but holds no cards");
        }
    }
}
