package com.mirboard.infra.ws;

import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.Team;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.messaging.DomainEventBus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 19(#3, D-75) — 게임중(IN_GAME) 탈주 확정 처리. 두 경로에서 호출:
 * (a) {@code RoomController.leave} 의 IN_GAME 명시 '나가기',
 * (b) {@link DesertionGraceScheduler} 의 끊김 후 유예 만료.
 *
 * <p>티츄는 2:2 고정이라 한 명이 빠지면 속행 불가 → <b>상대팀 승리</b>로
 * 매치를 즉시 종료한다. 기존 {@link TichuMatchCompleted} 경로를 재사용해
 * (winningTeam=상대팀, deserterUserId=탈주자) 1회만 발행하면
 * {@code MatchResultRecorder} 가 win/lose/ELO(봇 포함 시 ELO skip,
 * D-71) + 탈주자 desert_count 를 한 트랜잭션에 기록한다.
 *
 * <p>오케스트레이션 계층(MatchProgressService 와 동일 위치)에 둔다 —
 * 도메인 경계상 domain.game.tichu 가 domain.lobby.room 에 의존하면 안 되기
 * 때문(CLAUDE.md 모듈 경계).
 */
@Service
public class DesertionService {

    private static final Logger log = LoggerFactory.getLogger(DesertionService.class);

    /** 락 획득 재시도 (TTL 2s 보다 넉넉히 — 가상스레드라 블로킹 저렴). */
    private static final int LOCK_RETRIES = 30;
    private static final long LOCK_RETRY_MILLIS = 100L;

    private final RoomService roomService;
    private final TichuMatchStateStore matchStateStore;
    private final DomainEventBus events;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final BotUserRegistry bots;

    public DesertionService(RoomService roomService,
                            TichuMatchStateStore matchStateStore,
                            DomainEventBus events,
                            GameEventBroadcaster broadcaster,
                            RoomActionLock lock,
                            BotUserRegistry bots) {
        this.roomService = roomService;
        this.matchStateStore = matchStateStore;
        this.events = events;
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
            Team winner = Team.ofSeat(seat).opponent();

            TichuMatchState matchState = matchStateStore.load(roomId)
                    .orElseGet(() -> TichuMatchState.initial(
                            room.playerIds(), room.targetScore()));

            // 누적 점수는 그대로 보존하고 winningTeam 만 상대팀으로 강제.
            events.publish(new TichuMatchCompleted(
                    roomId,
                    room.playerIds(),
                    matchState.cumulativeA(),
                    matchState.cumulativeB(),
                    winner,
                    matchState.roundScores(),
                    deserterUserId));

            broadcaster.broadcast(
                    roomId,
                    List.<TichuEvent>of(new TichuEvent.MatchEnded(
                            winner,
                            matchState.scoresByTeam(),
                            matchState.roundScores().size())),
                    room.playerIds());

            roomService.markFinished(roomId);
            log.warn("Desertion processed: roomId={} deserterUserId={} seat={} winner={}",
                    roomId, deserterUserId, seat, winner);
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
