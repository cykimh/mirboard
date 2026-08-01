package com.mirboard.domain.game.core;

/**
 * 게임에 따라 의미가 있기도 없기도 한 방 설정 (D-106).
 *
 * <p>모든 게임에 통하는 설정(방 이름·인원·턴 제한·봇 채우기)은 여기 없다. 여기 있는 것은
 * <b>게임 구조에 묶여 있어 어떤 게임에는 아예 뜻이 없는</b> 것들뿐이다.
 *
 * <p>새 옵션은 enum 상수 하나만 늘리면 된다 — 포트 표면
 * ({@link GameDefinition#supportedRoomOptions()})은 그대로다.
 */
public enum RoomOption {

    /** 목표 점수에 먼저 도달하면 매치 종료. 라운드 수가 고정된 게임(스컬킹 10R)에는 뜻이 없다. */
    TARGET_SCORE,

    /** 팀 배정 정책(입장 순서/랜덤). 개인전에는 팀 자체가 없다. */
    TEAMS,

    /**
     * 방 단위 테이블 칩 내기(D-82). 정산이 팀 승패에 묶여 있어 현재는 티츄 전용이다 —
     * `RoomChipService` 가 티츄를 직접 참조하는 이유이자, 칩이 포트 밖에 있는 이유
     * (`docs/game-port.md` §2).
     */
    BETTING
}
