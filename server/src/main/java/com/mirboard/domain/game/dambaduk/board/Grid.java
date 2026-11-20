package com.mirboard.domain.game.dambaduk.board;

/**
 * 7×7 판의 면 번호 (룰 §1). 안쪽 면 84개에 0~83 을 매긴다:
 * <ul>
 *   <li>0~41 — 가로로 이웃한 두 칸 (x,y)·(x+1,y) 사이(세로 선분): {@code y·6 + x}</li>
 *   <li>42~83 — 세로로 이웃한 두 칸 (x,y)·(x,y+1) 사이(가로 선분): {@code 42 + y·7 + x}</li>
 * </ul>
 * 판 가장자리(바깥담)는 번호가 없다 — {@link #face} 가 −1 을 돌려준다. 면 번호는 상태 JSON·이벤트의 담 표기라 클라와의 계약이다.
 */
public final class Grid {

    public static final int SIZE = 7;
    public static final int CELLS = SIZE * SIZE;
    /** 세로 선분(가로 이웃) 면 수 = 6×7. */
    public static final int VERTICAL_FACES = (SIZE - 1) * SIZE;
    public static final int FACES = 2 * VERTICAL_FACES;

    private Grid() {
    }

    /** 칸 {@code c} 의 {@code d} 쪽 면 번호, 바깥담·판 밖이면 −1. */
    public static int face(Cell c, Dir d) {
        if (!c.onBoard() || !c.step(d).onBoard()) {
            return -1;
        }
        return switch (d) {
            case RIGHT -> c.y() * (SIZE - 1) + c.x();
            case LEFT -> c.y() * (SIZE - 1) + c.x() - 1;
            case DOWN -> VERTICAL_FACES + c.y() * SIZE + c.x();
            case UP -> VERTICAL_FACES + (c.y() - 1) * SIZE + c.x();
        };
    }

    /** 면 양쪽 칸 — 왼쪽/위 칸이 먼저. */
    public static Cell[] cellsOf(int face) {
        if (face < 0 || face >= FACES) {
            throw new IllegalArgumentException("face " + face);
        }
        if (face < VERTICAL_FACES) {
            int y = face / (SIZE - 1);
            int x = face % (SIZE - 1);
            return new Cell[] {new Cell(x, y), new Cell(x + 1, y)};
        }
        int f = face - VERTICAL_FACES;
        int y = f / SIZE;
        int x = f % SIZE;
        return new Cell[] {new Cell(x, y), new Cell(x, y + 1)};
    }
}
