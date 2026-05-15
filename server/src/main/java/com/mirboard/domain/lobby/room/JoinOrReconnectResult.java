package com.mirboard.domain.lobby.room;

/**
 * Phase 8A — `/api/rooms/{id}/join-or-reconnect` 결과. 클라가 분기 모드를 알 수
 * 있도록 함께 반환.
 *
 * <ul>
 *   <li>{@code JOINED} — 신규 플레이어로 입장 (capacity 증가).</li>
 *   <li>{@code RECONNECTED} — 이미 playerIds 에 있던 사용자가 재접속. Redis 변경 없음.</li>
 *   <li>{@code SPECTATING} — IN_GAME 방에 처음 들어온 비-참여자, 또는 이미 관전자.</li>
 * </ul>
 */
public record JoinOrReconnectResult(Mode mode, Room room) {
    public enum Mode { JOINED, RECONNECTED, SPECTATING }
}
