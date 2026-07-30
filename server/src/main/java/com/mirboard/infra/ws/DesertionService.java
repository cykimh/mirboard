package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 19(#3, D-75) — 게임중(IN_GAME) 탈주 확정 처리. 두 경로에서 호출:
 * (a) {@code RoomController.leave} 의 IN_GAME 명시 '나가기',
 * (b) {@link DesertionGraceScheduler} 의 끊김 후 유예 만료.
 *
 * <p>D-98 이후 본 서비스는 <b>게임을 모른다</b>. "한 명이 빠지면 매치가 어떻게 되는가"는
 * 게임 규칙이므로 {@link com.mirboard.domain.game.core.GameEngine#desert} 가 답하고,
 * 여기서는 그 앞뒤의 인프라 절차만 맡는다: 봇/비참가자 가드, 락 획득, 방 상태 확인,
 * 엔진이 만든 종료 이벤트 브로드캐스트, 방 FINISHED 마킹.
 *
 * <p>(티츄의 경우 엔진이 상대팀 승리로 매치를 종료하고 {@code TichuMatchCompleted} 를
 * 1회 발행해 {@code MatchResultRecorder} 가 win/lose/ELO + desert_count 를 한 트랜잭션에
 * 기록한다. 개인전 게임은 다른 답을 낼 수 있다.)
 */
@Service
public class DesertionService {

    private static final Logger log = LoggerFactory.getLogger(DesertionService.class);

    /** 락 획득 재시도 (TTL 2s 보다 넉넉히 — 가상스레드라 블로킹 저렴). */
    private static final int LOCK_RETRIES = 30;
    private static final long LOCK_RETRY_MILLIS = 100L;

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final BotUserRegistry bots;

    public DesertionService(RoomService roomService,
                            GameEngineProvider engines,
                            GameEventBroadcaster broadcaster,
                            RoomActionLock lock,
                            BotUserRegistry bots) {
        this.roomService = roomService;
        this.engines = engines;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.bots = bots;
    }

    /**
     * 탈주 확정 처리. 이미 종료/소멸됐거나 봇/비참가자면 멱등적으로 no-op.
     *
     * @return 실제로 탈주 처리(매치 강제종료)가 일어났으면 true.
     */
    public boolean processDesertion(String roomId, long deserterUserId) {
        if (bots.isBot(deserterUserId)) {
            return false; // 봇은 끊기지 않음 — 방어.
        }
        if (!acquireLock(roomId)) {
            log.warn("Desertion: lock 획득 실패 — skip. roomId={} userId={}",
                    roomId, deserterUserId);
            return false;
        }
        try {
            Room room;
            try {
                room = roomService.getRoom(roomId);
            } catch (RoomNotFoundException e) {
                return false; // 이미 소멸.
            }
            if (room.status() != RoomStatus.IN_GAME) {
                return false; // 이미 정상 종료 / 다른 탈주가 선처리 — 멱등.
            }
            int seat = room.playerIds().indexOf(deserterUserId);
            if (seat < 0) {
                return false; // 참가자 아님 — 방어.
            }

            List<GameEvent> outbound = new ArrayList<>();
            if (!engines.forRoom(room).desert(seat, deserterUserId, outbound)) {
                // 게임이 탈주로 보지 않음 (티츄: 매치가 이미 끝난 리매치 대기 방, D-82).
                return false;
            }

            broadcaster.broadcast(roomId, outbound, room.playerIds());
            roomService.markFinished(roomId);
            log.warn("Desertion processed: roomId={} deserterUserId={} seat={}",
                    roomId, deserterUserId, seat);
            return true;
        } catch (RuntimeException e) {
            log.error("Desertion processing error: roomId={} userId={} err={}",
                    roomId, deserterUserId, e.getMessage(), e);
            return false;
        } finally {
            lock.release(roomId);
        }
    }

    private boolean acquireLock(String roomId) {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            if (lock.tryAcquire(roomId)) {
                return true;
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
