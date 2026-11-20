package com.mirboard.domain.game.dambaduk.board;

/**
 * 칸 좌표 (룰 §1). 0~6, 왼쪽 위 (0,0). 판 밖 좌표도 만들 수 있다 — 거절은 엔진이 {@code OUT_OF_BOARD}·{@code PATH_BLOCKED} 로 한다
 * (클라가 보낸 값을 그대로 담아 검증하려고).
 */
public record Cell(int x, int y) {

    public static Cell ofIndex(int index) {
        return new Cell(index % Grid.SIZE, index / Grid.SIZE);
    }

    public boolean onBoard() {
        return x >= 0 && x < Grid.SIZE && y >= 0 && y < Grid.SIZE;
    }

    /** 칸 번호 {@code y·7 + x} — 터의 칸 목록·정렬에 쓴다. */
    public int index() {
        return y * Grid.SIZE + x;
    }

    public Cell step(Dir d) {
        return new Cell(x + d.dx(), y + d.dy());
    }

    public int manhattan(Cell other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    /** 화면 글자 — 가로 A~G, 세로 1~7 (B2 = (1,1)). 로그·테스트 메시지용. */
    public String label() {
        return onBoard() ? "" + (char) ('A' + x) + (y + 1) : "(" + x + "," + y + ")";
    }

    @Override
    public String toString() {
        return label();
    }
}
