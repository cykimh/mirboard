package com.mirboard.domain.game.omok;

/**
 * 오목 모드 (룰 §2, D-175·D-176). 방 설정 {@code TEAM_MODE} 의 값이고 좌석 수를 정한다.
 *
 * <p>팀은 두 모드 모두 {@code seat % 2} 다 — 1:1 에서는 좌석 하나가 한 편이라 같은 식으로 다룰 수 있다.
 */
public enum OmokMode {
    /** 1:1 — 정확히 2명. */
    SOLO(2),
    /** 2:2 팀전 — 정확히 4명, 좌석 0·2 대 1·3. 같은 팀이 같은 색 돌을 번갈아 둔다. */
    TEAMS_2V2(4);

    private final int seatCount;

    OmokMode(int seatCount) {
        this.seatCount = seatCount;
    }

    public int seatCount() {
        return seatCount;
    }
}
