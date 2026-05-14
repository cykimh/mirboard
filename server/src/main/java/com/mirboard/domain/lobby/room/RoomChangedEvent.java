package com.mirboard.domain.lobby.room;

/**
 * 방 상태가 바뀌었음을 알리는 도메인 이벤트. `currentState` 가 null 이면 방이
 * 삭제되었음(=DESTROYED). 그 외에는 가장 최근 스냅샷이 담긴다. 인프라(STOMP) 측
 * 리스너가 본 이벤트를 받아 `/topic/lobby/rooms` 로 브로드캐스트한다.
 */
public record RoomChangedEvent(String roomId, Room currentState) {

    public static RoomChangedEvent updated(Room room) {
        return new RoomChangedEvent(room.roomId(), room);
    }

    public static RoomChangedEvent destroyed(String roomId) {
        return new RoomChangedEvent(roomId, null);
    }

    public boolean isDestroyed() {
        return currentState == null;
    }
}
