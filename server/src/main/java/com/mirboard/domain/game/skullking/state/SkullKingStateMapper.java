package com.mirboard.domain.game.skullking.state;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 서버 상태 → 클라 뷰 변환 (D-102). State Hiding(D-01)의 스컬킹 경계는 두 가지다:
 * <ul>
 *   <li><b>손패</b> — 공개 뷰에는 장수만, 카드는 본인 뷰에만</li>
 *   <li><b>제출 전 예측값</b>(§5) — 전원 제출 전까지 공개 뷰는 제출 <i>여부</i>만 싣고,
 *       값은 본인 뷰에만. 전원 제출 후(Playing/RoundEnd)부터 공개</li>
 * </ul>
 * 획득 트릭 수·진행 중 트릭의 카드·누적 점수·탈주 좌석은 전부 공개 정보다.
 * <b>끝난 라운드의 기록과 매치 결과</b>도 공개다(D-120) — 기록은 정산이 끝난 라운드만
 * 담으므로 진행 중 라운드(특히 공개 전 예측값)는 그 경로로 새지 않는다.
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
                            Map<Integer, RoundScoreView> roundScores,
                            List<CompletedRoundView> completedRounds,
                            MatchResultView matchResult) {
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

    /** 라운드 정산 내역 — 현재 라운드(RoundEnd 에만)와 끝난 라운드 기록이 같이 쓴다. */
    public record RoundScoreView(int bid, int won, int base, int bonus, int total) {
    }

    /**
     * 끝난 라운드 하나 (D-120). {@code roundScores}(현재 라운드, RoundEnd 에만)와 이름이
     * 닮았지만 다른 것이다 — 이쪽은 정산이 끝난 라운드 1..N 의 누적 기록이다.
     */
    public record CompletedRoundView(int roundNumber, Map<Integer, RoundScoreView> scores) {
    }

    /**
     * 매치 결과 (D-120) — 매치가 끝난 뒤에만 값이 있다. {@code MATCH_ENDED} payload 와 같은
     * 모양이라 재접속한 클라가 종료 패널을 그대로 복원한다.
     *
     * @param roundsPlayed 완주 라운드 수 (조기 종료면 10 미만)
     */
    public record MatchResultView(List<Integer> winners,
                                  Map<Integer, Integer> finalScores,
                                  int roundsPlayed) {
    }

    /** 본인 전용 뷰 — 손패 + (미공개 구간의) 본인 예측값. */
    public record PrivateView(int seat, List<SkullCard> hand, Integer myBid) {
    }

    public static TableView toTableView(SkullKingState state, SkullKingMatchState match) {
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
                ? scoreViews(end.scores())
                : Map.of();

        List<CompletedRoundView> completedRounds = match.completedRounds().stream()
                .map(r -> new CompletedRoundView(r.roundNumber(), scoreViews(r.scores())))
                .toList();

        return new TableView(
                state.phaseName(),
                state.roundNumber(),
                handSizeOf(state),
                state.startSeat(),
                currentTurn,
                seats,
                trick,
                match.cumulativeScores(),
                match.desertedSeats().stream().sorted().toList(),
                roundScores,
                completedRounds,
                match.isMatchOver() ? matchResult(state, match) : null);
    }

    /**
     * 매치 결과. 승자·최종 점수·완주 라운드 수 모두 매치 상태의 권위값이다 — 완주 수는 매치가
     * 끝날 때 저장한 값이라(D-122) 엔진의 {@code MatchEnded.roundsPlayed}·DB 기록과 같다.
     * 라운드 상태에서 역산하지 않는 이유: 종료 뒤에도 상태가 바뀔 수 있었다(남은 턴 타이머가
     * 버려진 라운드를 RoundEnd 까지 밀면 0 이 1 이 됐다).
     *
     * <p>값이 없는 구 JSON(D-122 이전에 끝난 매치)만 예전 방식으로 떨어진다: 마지막 기록의
     * 번호, 기록도 없으면(D-120 이전) RoundEnd 는 그 라운드까지, 아니면 진행 중이던 라운드의 앞까지.
     */
    private static MatchResultView matchResult(SkullKingState state, SkullKingMatchState match) {
        int roundsPlayed = match.roundsPlayed() != null
                ? match.roundsPlayed()
                : legacyRoundsPlayed(state, match);
        return new MatchResultView(match.winners(), match.cumulativeScores(), roundsPlayed);
    }

    /** D-122 이전에 끝난 매치의 완주 수 역산 — 구 JSON 호환 전용. */
    private static int legacyRoundsPlayed(SkullKingState state, SkullKingMatchState match) {
        List<SkullKingMatchState.CompletedRound> history = match.completedRounds();
        if (!history.isEmpty()) {
            return history.get(history.size() - 1).roundNumber();
        }
        return state instanceof SkullKingState.RoundEnd
                ? state.roundNumber()
                : state.roundNumber() - 1;
    }

    /** 좌석별 점수 내역 → 뷰. total 은 컴포넌트가 아니라 메서드라 여기서 명시로 싣는다. */
    private static Map<Integer, RoundScoreView> scoreViews(Map<Integer, RoundScore> scores) {
        return scores.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new RoundScoreView(
                                e.getValue().bid(), e.getValue().won(),
                                e.getValue().base(), e.getValue().bonus(),
                                e.getValue().total())));
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
