package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;

/**
 * 테스트용 판 그림. {@code X} 흑, {@code O} 백, {@code .} 빈칸, {@code *} 판정할 자리(빈칸). 그림의 (0,0) 이 판의 (ox, oy).
 */
public final class OmokBoards {

    private OmokBoards() {
    }

    public record Diagram(Board board, Point star) {
    }

    public static Diagram diagram(int ox, int oy, String... rows) {
        Board b = Board.empty();
        Point star = null;
        for (int y = 0; y < rows.length; y++) {
            String row = rows[y];
            for (int x = 0; x < row.length(); x++) {
                Point p = new Point(ox + x, oy + y);
                switch (row.charAt(x)) {
                    case 'X' -> b = b.with(p, Stone.BLACK);
                    case 'O' -> b = b.with(p, Stone.WHITE);
                    case '*' -> star = p;
                    case '.' -> { }
                    default -> throw new IllegalArgumentException("bad char in " + row);
                }
            }
        }
        return new Diagram(b, star);
    }

    /** 원점 (3,3) — 그림이 판 가장자리에 닿지 않는다. */
    public static Diagram diagram(String... rows) {
        return diagram(3, 3, rows);
    }
}
