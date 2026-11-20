package com.mirboard.domain.game.dambaduk.board;

/**
 * 방향 (룰 §1). y 는 아래로 커진다. 선언 순서 {@code UP·RIGHT·DOWN·LEFT} 가 그대로 시간 초과 자동 수·합법 수 목록의 정렬 순서다(룰 §8).
 */
public enum Dir {
    UP(0, -1),
    RIGHT(1, 0),
    DOWN(0, 1),
    LEFT(-1, 0);

    private final int dx;
    private final int dy;

    Dir(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public Dir opposite() {
        return switch (this) {
            case UP -> DOWN;
            case RIGHT -> LEFT;
            case DOWN -> UP;
            case LEFT -> RIGHT;
        };
    }

    /** 꺾은돌의 둘째 걸음 조건 (룰 §4.2) — 가로·세로가 다르다. */
    public boolean isPerpendicularTo(Dir other) {
        return (dx == 0) != (other.dx == 0);
    }
}
