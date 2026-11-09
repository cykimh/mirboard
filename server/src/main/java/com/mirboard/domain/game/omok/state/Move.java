package com.mirboard.domain.game.omok.state;

import com.mirboard.domain.game.omok.board.Point;

/**
 * 수순의 한 수 — 둔 좌석과 자리. 색은 수 번호(짝수 = 흑)에서 파생한다. {@code timedOut} 은 시간 초과 자동 수(룰 §8).
 */
public record Move(int seat, int x, int y, boolean timedOut) {

    public Point point() {
        return new Point(x, y);
    }
}
