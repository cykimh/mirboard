package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.TichuEngine;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.web.MdcKeys;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 인게임 STOMP 액션 처리. `/app/room/{roomId}/action` 으로 들어오는 TichuAction 을
 * 다음 순서로 처리한다:
 * <ol>
 *   <li>Principal 에서 userId → 방 참가자인지 확인 후 seat 도출.</li>
 *   <li>{@link RoomActionLock} 으로 직렬화 락 획득 (실패 시 BUSY 에러).</li>
 *   <li>{@link TichuGameStateStore} 에서 현재 상태 로드.</li>
 *   <li>{@link TichuEngine#apply} 호출 — 검증/룰 적용.</li>
 *   <li>새 상태 저장 + 발생 이벤트들을 {@link GameEventBroadcaster} 로 분기 발행.</li>
 *   <li>RoundEnd 전이 시 매치 상태에 라운드 점수 누적 → 1000점 도달이면 매치 종료
 *       ({@link TichuMatchCompleted}, 방 FINISHED), 아니면 다음 라운드 자동 시작.</li>
 *   <li>락 해제.</li>
 * </ol>
 */
@Controller
public class GameStompController {

    private static final Logger log = LoggerFactory.getLogger(GameStompController.class);

    private final RoomService roomService;
    private final TichuGameStateStore stateStore;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.metrics.MirboardMetrics metrics;

    public GameStompController(RoomService roomService,
                               TichuGameStateStore stateStore,
                               GameEventBroadcaster broadcaster,
                               RoomActionLock lock,
                               MatchProgressService matchProgress,
                               com.mirboard.infra.bot.BotScheduler botScheduler,
                               com.mirboard.infra.metrics.MirboardMetrics metrics) {
        this.roomService = roomService;
        this.stateStore = stateStore;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.metrics = metrics;
    }

    @MessageMapping("/room/{roomId}/action")
    public void onAction(@DestinationVariable String roomId,
                         @Payload TichuAction action,
                         Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        try (var _ = MdcKeys.scope().userId(me.userId()).roomId(roomId)) {
            handleAction(roomId, action, me);
        }
    }

    private void handleAction(String roomId, TichuAction action, AuthPrincipal me) {
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            broadcaster.sendErrorTo(me.userId(), roomId, "ROOM_NOT_FOUND", "Room not found");
            return;
        }

        int seat = room.playerIds().indexOf(me.userId());
        if (seat < 0) {
            broadcaster.sendErrorTo(me.userId(), roomId, "NOT_IN_ROOM",
                    "User is not in the room");
            return;
        }

        if (!lock.tryAcquire(roomId)) {
            broadcaster.sendErrorTo(me.userId(), roomId, "BUSY",
                    "Another action is in progress");
            return;
        }
        try {
            TichuState state = stateStore.load(roomId).orElse(null);
            if (state == null) {
                broadcaster.sendErrorTo(me.userId(), roomId, "GAME_NOT_STARTED",
                        "No active game state for this room");
                return;
            }

            TichuEngine engine = new TichuEngine(new GameContext(roomId, room.playerIds()));
            TichuEngine.Result result;
            try {
                result = engine.apply(state, seat, action);
            } catch (TichuActionRejectedException rejected) {
                metrics.actionRejected();
                log.info("Action rejected: action={} reason={} message={}",
                        action.getClass().getSimpleName(), rejected.reason(),
                        rejected.getMessage());
                broadcaster.sendErrorTo(me.userId(), roomId, rejected.reason().name(),
                        rejected.getMessage());
                return;
            } catch (RuntimeException unexpected) {
                log.warn("Unexpected error applying action {} in room {}: {}",
                        action.getClass().getSimpleName(), roomId, unexpected.getMessage());
                broadcaster.sendErrorTo(me.userId(), roomId, "INTERNAL_ERROR",
                        "Failed to apply action");
                return;
            }

            stateStore.save(roomId, result.newState());

            // 라운드/매치 진행 처리 — RoundEnd 도달 시 매치 상태에 점수 누적하고
            // 매치 종료/다음 라운드 분기. 브로드캐스트 전에 추가 이벤트(RoundStarted /
            // MatchEnded) 를 같이 묶어서 한 번에 전송한다.
            List<TichuEvent> outbound = new ArrayList<>(result.events());
            if (result.newState() instanceof TichuState.RoundEnd ended) {
                matchProgress.onRoundEnd(roomId, room, ended, outbound);
            }

            broadcaster.broadcast(roomId, outbound, room.playerIds());
        } finally {
            lock.release(roomId);
        }
        // 락 해제 후 봇 차례면 비동기로 봇 액션 트리거.
        botScheduler.scheduleBots(roomId);
    }
}
