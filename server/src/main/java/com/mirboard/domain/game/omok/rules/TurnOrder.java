package com.mirboard.domain.game.omok.rules;

import com.mirboard.domain.game.omok.board.Stone;

/**
 * 좌석·팀·색 (룰 §2·§4). 팀은 두 모드 모두 {@code seat % 2}(1:1 은 좌석 하나가 한 편), 다음 차례는 좌석 +1. 첫 수 좌석이
 * 흑 팀이므로 좌석 +1 이 늘 상대 팀이라 색이 저절로 번갈아 돈다.
 */
public final class TurnOrder {

    private TurnOrder() {
    }

    public static int team(int seat) {
        return seat % 2;
    }

    public static Stone colorOf(int seat, int blackTeam) {
        return team(seat) == blackTeam ? Stone.BLACK : Stone.WHITE;
    }

    public static int next(int seat, int seatCount) {
        return (seat + 1) % seatCount;
    }
}
