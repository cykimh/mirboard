package com.mirboard.domain.game.omok.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import java.util.List;

/**
 * 판 결과 (룰 §10.1).
 *
 * @param winnerColor 이긴 색, 무승부면 null
 * @param winnerSeats 이긴 좌석들(오름차순), 무승부면 빈 목록
 * @param causeSeat   기권·시간패·탈주를 만든 좌석, 그 밖은 −1
 * @param winLine     5목이면 승리 줄 5점, 그 밖은 빈 목록
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record MatchResult(Stone winnerColor,
                          List<Integer> winnerSeats,
                          EndReason reason,
                          int causeSeat,
                          List<Point> winLine) {

    public MatchResult {
        winnerSeats = winnerSeats == null ? List.of() : List.copyOf(winnerSeats);
        winLine = winLine == null ? List.of() : List.copyOf(winLine);
    }

    /** 15줄 시절 결과를 19줄 판 가운데로 옮길 때(D-193). */
    public MatchResult shifted(int d) {
        return new MatchResult(winnerColor, winnerSeats, reason, causeSeat,
                winLine.stream().map(p -> new Point(p.x() + d, p.y() + d)).toList());
    }

    public boolean isDraw() {
        return winnerColor == null;
    }
}
