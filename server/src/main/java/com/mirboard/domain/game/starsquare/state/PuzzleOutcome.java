package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import java.util.List;

/**
 * 끝난 퍼즐 하나(`docs/rules-starsquare.md` §6.3·§8.1) — 끝나면 공개되는 것들: 순위·점수, 모두의 판, 정답 하나.
 *
 * @param results  좌석 순서(0..좌석 수−1)
 * @param grids    좌석 순서의 판
 * @param solution 서버가 아는 정답 하나
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PuzzleOutcome(int index, EndReason reason, List<SeatResult> results, List<Grid> grids, Grid solution) {

    public PuzzleOutcome {
        results = List.copyOf(results);
        grids = List.copyOf(grids);
    }

    /** 퍼즐이 끝난 이유(§6.3). */
    public enum EndReason {
        /** 살아 있는 좌석이 모두 완성. */
        ALL_SOLVED,
        /** 첫 완성 + 15초. */
        CLOSED,
        /** 퍼즐 제한. */
        TIME_UP
    }
}
