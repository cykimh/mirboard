package com.mirboard.domain.lobby.room;

/**
 * D-290 — 방장이 대기실에서 사람을 내보냈다. 좌석 변화는 {@link RoomChangedEvent} 가 따로 알리고, 이 이벤트는 <b>내보낸 사람의 화면</b>이
 * 스스로 나간 것·끊김 유예 만료와 구분해 "방장이 내보냈어요"를 띄우게 하려는 것이다. 로컬 발행만(D-116).
 */
public record RoomMemberKickedEvent(String roomId, long userId) {
}
