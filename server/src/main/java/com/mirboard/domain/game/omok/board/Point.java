package com.mirboard.domain.game.omok.board;

/**
 * 교차점 좌표 (룰 §1). 0~14, 왼쪽 위 (0,0), x 는 오른쪽·y 는 아래로 커진다. 판 밖 좌표도 만들 수 있다 — 거절은
 * 엔진이 {@code OUT_OF_BOARD} 로 한다(클라가 보낸 값을 그대로 담아 검증하려고).
 */
public record Point(int x, int y) {

    public boolean onBoard() {
        return x >= 0 && x < Board.SIZE && y >= 0 && y < Board.SIZE;
    }

    /** 체스판 거리 {@code max(|dx|, |dy|)} — 시간 초과 자동 수의 기준(룰 §8). */
    public int chebyshev(Point other) {
        return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
    }
}
