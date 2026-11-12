package com.mirboard.domain.game.omok.state;

import com.mirboard.domain.game.omok.board.Point;

/**
 * 수순의 한 수 — 둔 좌석과 자리. 색은 수 번호(짝수 = 흑)에서 파생한다. {@code timedOut} 은 시간 초과 자동 수(룰 §8).
 */
public record Move(int seat, int x, int y, boolean timedOut) {

    public Point point() {
        return new Point(x, y);
    }

    /** 15줄 시절 상태를 19줄 판 가운데로 옮길 때(D-193). */
    public Move shifted(int d) {
        return new Move(seat, x + d, y + d, timedOut);
    }
}
