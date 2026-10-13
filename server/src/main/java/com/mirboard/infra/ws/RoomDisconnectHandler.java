package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.room.GameAlreadyStartedException;
import com.mirboard.domain.lobby.room.NotInRoomException;
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
 *   <li>WAITING(D-134): 플레이어는 바로 내보내지 않는다 — 준비를 풀고(그 사람 없이 시작되지 않게)
 *       {@link WaitingLeaveGraceScheduler} 유예를 건다. 유예 안에 돌아오면 자리 유지, 아니면 만료가 leave
 *       (마지막 참가자면 방 소멸). 같은 방 라이브 세션이 남아 있으면 아무것도 안 한다. 관전자는 즉시
 *       stopSpectating. (D-75 는 즉시 leave 였다 — 휴대폰 방장이 초대 링크를 카톡으로 보내고 기다리는 사이
 *       방이 사라졌다.)</li>
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
    private final RoomPresence sessions;
    private final WaitingLeaveGraceScheduler waitingGrace;

    public RoomDisconnectHandler(RoomService roomService,
                                 DesertionGraceScheduler graceScheduler,
                                 PlayerPresenceNotifier presence,
                                 RoomPresence sessions,
                                 WaitingLeaveGraceScheduler waitingGrace) {
        this.roomService = roomService;
        this.graceScheduler = graceScheduler;
        this.presence = presence;
        this.sessions = sessions;
        this.waitingGrace = waitingGrace;
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
                    // D-134(FR-2) — 같은 방 라이브 세션(두 탭 중 남은 탭·먼저 붙은 새 소켓)이 있으면 아직 접속 중이다.
                    // IN_GAME 갈래와 같은 가드. presence.leave 가 끊긴 세션을 먼저 뺀 뒤 불리므로 남은 세션만 센다.
                    if (sessions.hasLiveSession(userId, roomId)) {
                        return;
                    }
                    // D-134(FR-1) — 바로 내보내지 않는다. 준비를 먼저 풀어 그 사람 없이 시작되지 않게 하고 유예를 건다.
                    unready(roomId, userId);
                    waitingGrace.scheduleGrace(roomId, userId);
                    log.info("WS disconnect → WAITING grace: roomId={} userId={}", roomId, userId);
                } else if (room.spectatorIds().contains(userId)) {
                    roomService.stopSpectating(roomId, userId);
                    log.info("WS disconnect → WAITING spectator cleanup: roomId={} userId={}", roomId, userId);
                }
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
     * 방 토픽 재구독(재접속) 시 호출. 대기실 유예(D-134)는 조용히 취소한다. 해당 유저에 대기 중인 탈주 유예가 있었으면
     * 취소하고 다른 좌석에 RECONNECTED 를 알린다(= 끊겼다가 돌아옴). 유예가 없었으면
     * 일반 구독이므로 아무 것도 하지 않는다(getRoom 조회도 생략).
     */
    public void onReconnect(String roomId, long userId) {
        // D-134 — 대기실 유예도 취소한다. 알림은 없다(RECONNECTED 는 게임판 배지용) — 풀었던 준비도 그대로 둔다.
        waitingGrace.cancelIfPending(roomId, userId);
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

    /**
     * D-134 — 끊긴 사람의 준비를 푼다. 메타 갱신이 나가는 기존 경로({@link RoomService#setReady})라 다른 사람 화면의 "준비됨"도
     * 바로 꺼진다. 다시 읽은 뒤 방이 사라졌거나·시작됐거나·이미 나간 경합이면 건너뛴다 — 유예 만료가 상태를 다시 본다.
     */
    private void unready(String roomId, long userId) {
        try {
            roomService.setReady(roomId, userId, false);
        } catch (RoomNotFoundException | GameAlreadyStartedException | NotInRoomException e) {
            log.info("WS disconnect → WAITING 준비 해제 생략: roomId={} userId={} reason={}", roomId, userId, e.toString());
        } catch (RuntimeException e) {
            log.warn("WS disconnect → WAITING 준비 해제 실패 — 유예는 건다: roomId={} userId={}", roomId, userId, e);
        }
    }
}
