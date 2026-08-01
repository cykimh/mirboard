package com.mirboard.domain.lobby.room;

import com.mirboard.domain.game.core.RoomOption;

/**
 * 게임이 쓰지 않는 방 설정에 기본값 아닌 값이 왔다 (D-106).
 *
 * <p>클라가 이 옵션을 안 보내게 고쳐도 서버는 검증한다 — 클라 입력은 검증 대상이지 신뢰
 * 대상이 아니다. 조용히 무시하지 않는 이유는 그 조용함이 이 결함을 만들었기 때문이다:
 * 스컬킹 방에 stake 를 걸면 칩은 생기지 않는데 봇만 금지되는 상태가 오래 남아 있었다.
 */
public final class UnsupportedRoomOptionException extends RuntimeException {

    private final String gameType;
    private final RoomOption option;

    public UnsupportedRoomOptionException(String gameType, RoomOption option) {
        super("Game " + gameType + " does not support room option " + option);
        this.gameType = gameType;
        this.option = option;
    }

    public String gameType() {
        return gameType;
    }

    public RoomOption option() {
        return option;
    }
}
