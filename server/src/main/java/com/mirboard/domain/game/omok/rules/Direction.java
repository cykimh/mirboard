package com.mirboard.domain.game.omok.rules;

/**
 * 줄의 네 방향 — 승리 줄을 찾는 순서이기도 하다(룰 §5: 가로 → 세로 → {@code \} → {@code /}). y 가 아래로 커지므로
 * {@code \} 는 (1,1), {@code /} 는 (1,−1).
 */
public enum Direction {
    HORIZONTAL(1, 0),
    VERTICAL(0, 1),
    DIAGONAL_DOWN(1, 1),
    DIAGONAL_UP(1, -1);

    public final int dx;
    public final int dy;

    Direction(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }
}
