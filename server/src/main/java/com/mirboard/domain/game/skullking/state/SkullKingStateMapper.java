package com.mirboard.domain.game.skullking.state;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버 상태 → 클라 뷰 변환 (D-102). State Hiding(D-01)의 스컬킹 경계는 두 가지다:
 * <ul>
 *   <li><b>손패</b> — 공개 뷰에는 장수만, 카드는 본인 뷰에만</li>
 *   <li><b>제출 전 예측값</b>(§5) — 전원 제출 전까지 공개 뷰는 제출 <i>여부</i>만 싣고,
 *       값은 본인 뷰에만. 전원 제출 후(Playing/RoundEnd)부터 공개</li>
 * </ul>
 * 획득 트릭 수·진행 중 트릭의 카드·누적 점수·탈주 좌석은 전부 공개 정보다.
 */
public final class SkullKingStateMapper {

    private SkullKingStateMapper() {
    }

    /** 공개 뷰 — 참가자·관전자 전원이 본다. 손패 카드·미공개 예측값 없음. */
    public record TableView(String phase,
                            int roundNumber,
                            int handSize,
                            int startSeat,
                            int currentTurnSeat,
                            List<SeatView> seats,
                            List<PlayedCardView> trick,
                            Map<Integer, Integer> cumulativeScores,
                            List<Integer> desertedSeats,
                            Map<Integer, RoundScoreView> roundScores) {
    }

    /**
     * 좌석 하나의 공개 상태.
     *
     * @param bid 전원 제출 후에만 값, 그 전엔 null (§5 — hasBid 로만 제출 여부 노출)
     */
    public record SeatView(int seat, int handCount, boolean hasBid, Integer bid, int tricksWon) {
    }

    /** 트릭에 공개된 카드 한 장 — 티그리스는 선언까지 공개(판정 근거). */
    public record PlayedCardView(int seat, SkullCard card, TigressMode declaredAs) {
    }

    /** 라운드 정산 내역 (RoundEnd 에만). */
    public record RoundScoreView(int bid, int won, int base, int bonus, int total) {
    }

    /** 본인 전용 뷰 — 손패 + (미공개 구간의) 본인 예측값. */
    public record PrivateView(int seat, List<SkullCard> hand, Integer myBid) {
    }

    public static TableView toTableView(SkullKingState state,
                                        Map<Integer, Integer> cumulativeScores,
                                        Set<Integer> desertedSeats) {
        boolean bidsRevealed = bidsRevealed(state);
        List<SeatView> seats = state.players().stream()
                .map(p -> new SeatView(
                        p.seat(),
                        p.handSize(),
                        p.hasBid(),
                        bidsRevealed && p.hasBid() ? p.bid() : null,
                        p.tricksWonCount()))
                .toList();

        List<PlayedCardView> trick = state instanceof SkullKingState.Playing playing
                ? playing.trick().played().stream()
                        .map(pc -> new PlayedCardView(pc.seat(), pc.card(), pc.declaredAs()))
                        .toList()
                : List.of();

        int currentTurn = state instanceof SkullKingState.Playing playing
                ? playing.currentTurnSeat()
                : -1;

        Map<Integer, RoundScoreView> roundScores = state instanceof SkullKingState.RoundEnd end
                ? end.scores().entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                e -> new RoundScoreView(
                                        e.getValue().bid(), e.getValue().won(),
                                        e.getValue().base(), e.getValue().bonus(),
                                        e.getValue().total())))
                : Map.of();

        return new TableView(
                state.phaseName(),
                state.roundNumber(),
                handSizeOf(state),
                state.startSeat(),
                currentTurn,
                seats,
                trick,
                cumulativeScores,
                desertedSeats.stream().sorted().toList(),
                roundScores);
    }

    public static PrivateView toPrivateView(SkullKingState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            return new PrivateView(seat, List.of(), null);
        }
        PlayerState player = state.players().get(seat);
        Integer myBid = !bidsRevealed(state) && player.hasBid() ? player.bid() : null;
        return new PrivateView(seat, player.hand(), myBid);
    }

    /** 예측값이 공개된 상태인가 — Bidding 은 전원 제출 전이므로 미공개 (§5). */
    private static boolean bidsRevealed(SkullKingState state) {
        return !(state instanceof SkullKingState.Bidding);
    }

    /** 이 라운드의 트릭 수 = 분배 장수. 손패가 줄어도 변하지 않는 값이라 역산한다. */
    private static int handSizeOf(SkullKingState state) {
        return com.mirboard.domain.game.skullking.Dealer.handSize(
                state.roundNumber(), state.seatCount());
    }
}
