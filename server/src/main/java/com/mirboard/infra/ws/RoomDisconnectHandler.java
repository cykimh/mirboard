package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
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
    private final PlayerPresenceNotifier presence;
    private final WsSessionRegistry sessions;

    public RoomDisconnectHandler(RoomService roomService,
                                 DesertionGraceScheduler graceScheduler,
                                 PlayerPresenceNotifier presence,
                                 WsSessionRegistry sessions) {
        this.roomService = roomService;
        this.graceScheduler = graceScheduler;
        this.presence = presence;
        this.sessions = sessions;
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
                    // 모바일 재접속: 새 소켓 SUBSCRIBE 가 옛 소켓의 늦은 close 보다 먼저
                    // 처리되면, 이 끊김 시점에 이미 같은 방의 라이브 세션이 있다. 그러면
                    // 실제로는 접속 중이므로 유예/끊김 알림을 건너뛴다 — 거짓 '연결 끊김'
                    // 배지가 다른 좌석에 영구히 남는 것을 방지(RECONNECTED 가 안 와서 안 지워짐).
                    if (sessions.hasLiveSession(userId, roomId)) {
                        return;
                    }
                    graceScheduler.scheduleGrace(roomId, userId);
                    int seat = room.playerIds().indexOf(userId);
                    if (seat >= 0) {
                        presence.disconnected(roomId, seat);
                    }
                } else if (room.spectatorIds().contains(userId)) {
                    roomService.stopSpectating(roomId, userId);
                }
            }
            case FINISHED -> {
                /* no-op */
            }
        }
    }

    /**
     * 방 토픽 재구독(재접속) 시 호출. 해당 유저에 대기 중인 탈주 유예가 있었으면
     * 취소하고 다른 좌석에 RECONNECTED 를 알린다(= 끊겼다가 돌아옴). 유예가 없었으면
     * 일반 구독이므로 아무 것도 하지 않는다(getRoom 조회도 생략).
     */
    public void onReconnect(String roomId, long userId) {
        if (!graceScheduler.cancelIfPending(roomId, userId)) {
            return;
        }
        try {
            Room room = roomService.getRoom(roomId);
            if (room.status() != RoomStatus.IN_GAME) {
                return;
            }
            int seat = room.playerIds().indexOf(userId);
            if (seat >= 0) {
                presence.reconnected(roomId, seat);
            }
        } catch (RoomNotFoundException ignored) {
            // 이미 소멸 — 알릴 대상 없음.
        }
    }
}
