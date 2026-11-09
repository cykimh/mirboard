package com.mirboard.domain.game.omok.rules;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 승리 판정 (룰 §5). 흑은 렌주에서 정확히 5, 그 밖은 5 이상. 백은 늘 5 이상.
 */
public final class WinRule {

    private WinRule() {
    }

    /** 이 길이의 연속이 그 색에게 이기는 줄인가. */
    public static boolean isWinningLength(int length, Stone color, OmokRule rule) {
        if (color == Stone.BLACK && rule.blackExactFive()) {
            return length == 5;
        }
        return length >= 5;
    }

    /**
     * 판에 이미 놓인 돌 p 가 만든 승리 줄 — 가로 → 세로 → {@code \} → {@code /} 순으로 처음 찾은 방향의 5점(장목이면 p 에 가까운
     * 5점). 없으면 empty.
     */
    public static Optional<List<Point>> winLine(Board board, Point p, OmokRule rule) {
        Stone color = board.stoneAt(p);
        if (color == null) {
            return Optional.empty();
        }
        byte[] cells = board.cells();
        byte code = Board.code(color);
        for (Direction d : Direction.values()) {
            int[] r = Lines.run(cells, p.x(), p.y(), d, code);
            int len = r[1] - r[0] + 1;
            if (!isWinningLength(len, color, rule)) {
                continue;
            }
            // p 를 가운데 가깝게 두는 5칸 창 — 구간 안으로 자른다.
            int start = Math.max(r[0], Math.min(-2, r[1] - 4));
            List<Point> line = new ArrayList<>(5);
            for (int k = start; k < start + 5; k++) {
                line.add(new Point(p.x() + k * d.dx, p.y() + k * d.dy));
            }
            return Optional.of(List.copyOf(line));
        }
        return Optional.empty();
    }
}
