package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import com.mirboard.domain.game.starsquare.puzzle.Progress;

/**
 * 좌석 하나의 지금 퍼즐 판(`docs/rules-starsquare.md` §6·§7). 사람 판은 본인 비공개 뷰로만 나가고 남에게는 진행도만 간다(§5.1).
 *
 * @param grid       판 전체(단서 포함)
 * @param version    마지막으로 받은 {@code SYNC} 버전(퍼즐 시작 0)
 * @param filled     진행도 — 채운 칸(저장값, 판에서 다시 계산한 값과 같아야 한다 — 불변식)
 * @param lines      진행도 — 맞은 줄
 * @param solvePlace 완성 순서(1 부터), 아직이면 0
 * @param elapsedMs  완성까지 걸린 시간(ms), 아직이면 −1
 * @param pace       봇 좌석의 페이스, 사람이면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatBoard(Grid grid, long version, int filled, int lines, int solvePlace, long elapsedMs, BotPlan pace) {

    /** 단서만 든 새 판. */
    public static SeatBoard fresh(Grid clues, BotPlan pace) {
        Progress p = MagicCheck.progress(clues);
        return new SeatBoard(clues, 0, p.filled(), p.lines(), 0, -1, pace);
    }

    public boolean solved() {
        return solvePlace > 0;
    }

    public Progress progress() {
        return new Progress(filled, lines);
    }

    /** 판을 바꾼다(진행도는 다시 계산). */
    public SeatBoard withGrid(Grid next, long nextVersion) {
        Progress p = MagicCheck.progress(next);
        return new SeatBoard(next, nextVersion, p.filled(), p.lines(), solvePlace, elapsedMs, pace);
    }

    public SeatBoard solvedAs(int place, long elapsed) {
        return new SeatBoard(grid, version, filled, lines, place, elapsed, pace);
    }

    public SeatBoard withPace(BotPlan next) {
        return new SeatBoard(grid, version, filled, lines, solvePlace, elapsedMs, next);
    }
}
