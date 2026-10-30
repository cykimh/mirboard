package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.GameNotInProgressException;
import com.mirboard.domain.lobby.room.NotHostException;
import com.mirboard.domain.lobby.room.NotInRoomException;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D-162 — 방장이 게임 중 끊긴 사람을 내보낸다. 탈주 유예(D-158 — 3분) 내내 기다리면 여러 명이 하는 판이 멈춘다.
 *
 * <p>끊김 판정은 유예 만료({@link DesertionGraceScheduler#handle})와 같은 {@link RoomPresence} 다 — 그 방에 라이브 세션이
 * 남아 있으면 내보내지 않는다. 통과하면 걸려 있던 유예를 지우고(뒤늦게 발화해도 멱등이지만 로그가 남지 않게) 유예 만료와 같은
 * {@link DesertionService#processDesertion} 을 바로 부른다 — 기록(탈주·패)과 게임별 결과(티츄는 매치 종료)도 같다.
 */
@Service
public class HostKickService {

    private static final Logger log = LoggerFactory.getLogger(HostKickService.class);

    private final RoomService rooms;
    private final RoomPresence presence;
    private final DesertionGraceScheduler grace;
    private final DesertionService desertion;
    private final BotUserRegistry bots;

    public HostKickService(RoomService rooms,
                           RoomPresence presence,
                           DesertionGraceScheduler grace,
                           DesertionService desertion,
                           BotUserRegistry bots) {
        this.rooms = rooms;
        this.presence = presence;
        this.grace = grace;
        this.desertion = desertion;
        this.bots = bots;
    }

    /**
     * @return 탈주로 처리됐으면 true. 이미 탈주한 좌석 등으로 처리할 것이 없으면 false(오류 아님).
     */
    public boolean kick(String roomId, long hostId, long targetUserId) {
        Room room = rooms.getRoom(roomId);
        if (room.hostId() != hostId) {
            throw new NotHostException(roomId);
        }
        if (room.status() != RoomStatus.IN_GAME) {
            throw new GameNotInProgressException(roomId);
        }
        if (targetUserId == hostId || bots.isBot(targetUserId) || !room.playerIds().contains(targetUserId)) {
            throw new NotInRoomException(roomId);
        }
        if (presence.hasLiveSession(targetUserId, roomId)) {
            throw new PlayerStillConnectedException(targetUserId);
        }
        grace.cancelIfPending(roomId, targetUserId);
        boolean processed = desertion.processDesertion(roomId, targetUserId);
        log.info("Host kick: roomId={} host={} target={} processed={}", roomId, hostId, targetUserId, processed);
        return processed;
    }
}
