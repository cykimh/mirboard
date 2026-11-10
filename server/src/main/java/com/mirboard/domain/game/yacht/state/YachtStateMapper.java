package com.mirboard.domain.game.yacht.state;

import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.score.Scoring;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 공개 뷰 (`docs/rules-yacht.md` §9). 숨길 정보가 없어 상태의 전부를 보이지만, 저장 전용 필드(액션 수·차례 시작 시각)는
 * 빼고 계산값(소계·보너스·총점·후보 점수·남은 시간)을 더한다. 비공개 뷰는 없다.
 */
public final class YachtStateMapper {

    private YachtStateMapper() {
    }

    /**
     * @param turnRemainingMs 차례 마감까지 남은 시간(엔진 계산, 0 미만은 0), 제한이 꺼졌거나 끝났으면 null
     */
    public static PublicView toPublicView(YachtState state, Long turnRemainingMs) {
        List<SeatView> seats = IntStream.range(0, state.seatCount())
                .mapToObj(s -> {
                    Scorecard c = state.card(s);
                    return new SeatView(s, c.scores(), c.upperSubtotal(), c.bonus(), c.total(), !state.alive(s));
                })
                .toList();
        TurnState turn = state.turn();
        Map<Category, Integer> candidates = turn.dice() == null || state.ended() || state.simultaneous()
                ? Map.of()
                : Scoring.candidates(state.card(state.turnSeat()), turn.dice());
        Long remaining = turnRemainingMs == null ? null : Math.max(0L, turnRemainingMs);
        List<SeatTurnView> turns = state.simultaneous()
                ? IntStream.range(0, state.seatCount()).mapToObj(s -> seatTurn(state, s)).toList()
                : List.of();
        return new PublicView(state.phaseName(), seats, state.round(), state.turnSeat(), turn.dice(), turn.held(),
                turn.rolls(), candidates, remaining, state.result(), state.simultaneous(), turns);
    }

    private static SeatTurnView seatTurn(YachtState state, int seat) {
        TurnState t = state.turnOf(seat);
        Map<Category, Integer> candidates = t.dice() == null ? Map.of() : Scoring.candidates(state.card(seat), t.dice());
        return new SeatTurnView(seat, t.dice(), t.held(), t.rolls(), state.pending(seat), candidates);
    }

    /**
     * D-202 — 동시 진행의 좌석별 주사위.
     *
     * @param pending    이번 라운드를 아직 기록하지 않았다(살아 있는 좌석만)
     * @param candidates 그 좌석의 빈 칸 → 지금 주사위 점수(굴리기 전·기록 뒤엔 빈 맵)
     */
    public record SeatTurnView(int seat, Dice dice, HoldMask held, int rolls, boolean pending,
                               Map<Category, Integer> candidates) {
    }

    public record SeatView(int seat, Map<Category, Integer> scores, int upperSubtotal, int bonus, int total,
                           boolean deserted) {
    }

    /**
     * @param dice       지금 차례의 눈, 첫 굴림 전이면 null
     * @param candidates 차례인 좌석의 빈 칸 → 지금 주사위 점수(굴리기 전·끝난 뒤엔 빈 맵)
     * @param simultaneous D-202 — 동시 진행. 이때 {@code turnSeat} −1, 위 주사위 셋은 비고 좌석별 주사위는 {@code turns}
     * @param turns      D-202 — 동시 진행의 좌석별 주사위(좌석 순). 차례대로면 빈 목록
     * @param turnRemainingMs 차례(동시 진행이면 라운드) 마감까지 남은 시간
     */
    public record PublicView(String phase,
                             List<SeatView> seats,
                             int round,
                             int turnSeat,
                             Dice dice,
                             HoldMask held,
                             int rolls,
                             Map<Category, Integer> candidates,
                             Long turnRemainingMs,
                             MatchResult result,
                             boolean simultaneous,
                             List<SeatTurnView> turns) {
    }
}
