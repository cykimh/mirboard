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
        Map<Category, Integer> candidates = turn.dice() == null || state.ended()
                ? Map.of()
                : Scoring.candidates(state.card(state.turnSeat()), turn.dice());
        Long remaining = turnRemainingMs == null ? null : Math.max(0L, turnRemainingMs);
        return new PublicView(state.phaseName(), seats, state.round(), state.turnSeat(), turn.dice(), turn.held(),
                turn.rolls(), candidates, remaining, state.result());
    }

    public record SeatView(int seat, Map<Category, Integer> scores, int upperSubtotal, int bonus, int total,
                           boolean deserted) {
    }

    /**
     * @param dice       지금 차례의 눈, 첫 굴림 전이면 null
     * @param candidates 차례인 좌석의 빈 칸 → 지금 주사위 점수(굴리기 전·끝난 뒤엔 빈 맵)
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
                             MatchResult result) {
    }
}
