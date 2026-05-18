package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 19(#1, D-75) — STOMP 세션 끊김(새로고침/탭닫기/네트워크)이 발생했을 때
 * 방 상태별 처리.
 *
 * <ul>
 *   <li>WAITING: 즉시 leave(플레이어) / stopSpectating(관전자). 마지막
 *       참가자면 방이 즉시 소멸한다.</li>
 *   <li>IN_GAME: 즉시 퇴장하지 않고 재접속 유예 — {@link DesertionGraceScheduler}
 *       (Phase 19#5) 가 유예시간 후 미복귀 시 탈주로 확정.</li>
 *   <li>FINISHED / 없는 방: no-op.</li>
 * </ul>
 */
@Component
public class RoomDisconnectHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomDisconnectHandler.class);

    private final RoomService roomService;
    private final DesertionGraceScheduler graceScheduler;

    public RoomDisconnectHandler(RoomService roomService,
                                 DesertionGraceScheduler graceScheduler) {
        this.roomService = roomService;
        this.graceScheduler = graceScheduler;
    }

    public void onDisconnect(String roomId, long userId) {
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            return; // 이미 소멸.
        }

        switch (room.status()) {
            case WAITING -> {
                if (room.playerIds().contains(userId)) {
                    roomService.leaveRoom(roomId, userId);
                } else if (room.spectatorIds().contains(userId)) {
                    roomService.stopSpectating(roomId, userId);
                }
                log.info("WS disconnect → WAITING cleanup: roomId={} userId={}", roomId, userId);
            }
            case IN_GAME -> {
                // 플레이어만 탈주 유예 대상 — 관전자 끊김은 게임 영향 없음.
                if (room.playerIds().contains(userId)) {
                    graceScheduler.scheduleGrace(roomId, userId);
                } else if (room.spectatorIds().contains(userId)) {
                    roomService.stopSpectating(roomId, userId);
                }
            }
            case FINISHED -> {
                /* no-op */
            }
        }
    }
}
