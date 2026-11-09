package com.mirboard.domain.game.omok.rules;

import com.mirboard.domain.game.omok.board.Board;

/**
 * 가변 판({@code byte[]}) 위의 줄 계산. 판 밖은 {@link #WALL} 로 읽는다. 금수 판정의 놓았다 거두기를 여기서 한다.
 */
final class Lines {

    static final byte WALL = 3;

    private Lines() {
    }

    static byte at(byte[] cells, int x, int y) {
        if (x < 0 || x >= Board.SIZE || y < 0 || y >= Board.SIZE) {
            return WALL;
        }
        return cells[Board.index(x, y)];
    }

    static void set(byte[] cells, int x, int y, byte v) {
        cells[Board.index(x, y)] = v;
    }

    /** (x,y) 를 지나 d 방향으로 같은 색이 이어진 구간의 [시작, 끝] 오프셋(그 칸 기준). (x,y) 는 color 여야 한다. */
    static int[] run(byte[] cells, int x, int y, Direction d, byte color) {
        int back = 0;
        while (at(cells, x - (back + 1) * d.dx, y - (back + 1) * d.dy) == color) {
            back++;
        }
        int fwd = 0;
        while (at(cells, x + (fwd + 1) * d.dx, y + (fwd + 1) * d.dy) == color) {
            fwd++;
        }
        return new int[] {-back, fwd};
    }

    static int runLength(byte[] cells, int x, int y, Direction d, byte color) {
        int[] r = run(cells, x, y, d, color);
        return r[1] - r[0] + 1;
    }
}
