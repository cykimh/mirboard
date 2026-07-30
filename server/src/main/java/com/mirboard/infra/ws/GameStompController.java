package com.mirboard.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameActionRejectedException;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.web.MdcKeys;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 인게임 STOMP 액션 처리. `/app/room/{roomId}/action` 으로 들어오는 액션을 다음 순서로
 * 처리한다:
 * <ol>
 *   <li>Principal 에서 userId → 방 참가자인지 확인 후 seat 도출.</li>
 *   <li>방의 gameType 으로 {@link GameEngineProvider#forRoom} 엔진 획득 →
 *       {@link GameEngine#actionType()} 으로 원본 JSON 역직렬화.</li>
 *   <li>{@link RoomActionLock} 으로 직렬화 락 획득 (실패 시 BUSY 에러).</li>
 *   <li>{@link GameEngine#loadState()} 로 현재 상태 로드.</li>
 *   <li>{@link GameEngine#apply} 호출 — 검증/룰 적용.</li>
 *   <li>새 상태 저장 + {@link MatchProgressService#advance} 로 라운드/매치 진행 →
 *       발생 이벤트들을 {@link GameEventBroadcaster} 로 한 번에 분기 발행.</li>
 *   <li>락 해제 후 봇/타임아웃 재스케줄.</li>
 * </ol>
 *
 * <p>D-98: 과거 {@code @Payload TichuAction} 으로 타입이 고정돼 있어 티츄 외의 게임은
 * 이 목적지를 쓸 수 없었다. 목적지는 하나로 두고(클라 계약 무변경) 방 → gameType 으로
 * 역직렬화 타깃을 고른다 — 본 클래스에 게임 이름은 등장하지 않는다.
 */
@Controller
public class GameStompController {

    private static final Logger log = LoggerFactory.getLogger(GameStompController.class);

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final ObjectMapper objectMapper;
    private final GameEventBroadcaster broadcaster;
    private final RoomActionLock lock;
    private final MatchProgressService matchProgress;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final com.mirboard.infra.metrics.MirboardMetrics metrics;

    public GameStompController(RoomService roomService,
                               GameEngineProvider engines,
                               ObjectMapper objectMapper,
                               GameEventBroadcaster broadcaster,
                               RoomActionLock lock,
                               MatchProgressService matchProgress,
                               com.mirboard.infra.bot.BotScheduler botScheduler,
                               com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout,
                               com.mirboard.infra.metrics.MirboardMetrics metrics) {
        this.roomService = roomService;
        this.engines = engines;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.lock = lock;
        this.matchProgress = matchProgress;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
        this.metrics = metrics;
    }

    /**
     * payload 를 {@code Map} 으로 받는 이유: 이 목적지의 본문은 게임별로 다른 타입이라
     * 하드타입을 쓸 수 없고, Jackson 트리 타입({@code JsonNode})도 쓸 수 없다 —
     * Spring Framework 7 의 브로커 컨버터는 Jackson 3 기반이라 Jackson 2 의
     * {@code JsonNode} 를 역직렬화 대상으로 받지 못한다(MessageConversionException).
     * 버전 중립인 {@code Map} 으로 받아 우리 {@link ObjectMapper}(Redis 상태 직렬화와
     * 동일 인스턴스)로 게임별 액션 타입으로 변환한다.
     */
    @MessageMapping("/room/{roomId}/action")
    public void onAction(@DestinationVariable String roomId,
                         @Payload Map<String, Object> payload,
                         Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        try (var _ = MdcKeys.scope().userId(me.userId()).roomId(roomId)) {
            handleAction(roomId, payload, me);
        }
    }

    private void handleAction(String roomId, Map<String, Object> payload, AuthPrincipal me) {
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

        GameEngine engine;
        try {
            engine = engines.forRoom(room);
        } catch (GameNotFoundException e) {
            broadcaster.sendErrorTo(me.userId(), roomId, "GAME_NOT_AVAILABLE",
                    "Game is not available: " + room.gameType());
            return;
        }

        GameAction action = parseAction(engine, payload, room.gameType(), roomId, me);
        if (action == null) {
            return;  // parseAction 이 이미 ERROR 를 보냈다.
        }

        if (!lock.tryAcquire(roomId)) {
            broadcaster.sendErrorTo(me.userId(), roomId, "BUSY",
                    "Another action is in progress");
            return;
        }
        try {
            GameState state = engine.loadState().orElse(null);
            if (state == null) {
                broadcaster.sendErrorTo(me.userId(), roomId, "GAME_NOT_STARTED",
                        "No active game state for this room");
                return;
            }

            GameEngine.Result result;
            try {
                result = engine.apply(state, seat, action);
            } catch (GameActionRejectedException rejected) {
                metrics.actionRejected();
                log.info("Action rejected: action={} code={} message={}",
                        action.getClass().getSimpleName(), rejected.code(),
                        rejected.getMessage());
                broadcaster.sendErrorTo(me.userId(), roomId, rejected.code(),
                        rejected.getMessage());
                return;
            } catch (RuntimeException unexpected) {
                log.warn("Unexpected error applying action {} in room {}: {}",
                        action.getClass().getSimpleName(), roomId, unexpected.getMessage());
                broadcaster.sendErrorTo(me.userId(), roomId, "INTERNAL_ERROR",
                        "Failed to apply action");
                return;
            }

            engine.saveState(result.newState());

            // 라운드/매치 진행 처리 — 라운드 종료 도달 시 엔진이 점수를 누적하고 매치
            // 종료/다음 라운드로 분기한다. 브로드캐스트 전에 추가 이벤트(RoundStarted /
            // MatchEnded)를 같이 묶어서 한 번에 전송한다.
            List<GameEvent> outbound = new ArrayList<>(result.events());
            matchProgress.advance(engine, room, result.newState(), outbound);

            broadcaster.broadcast(roomId, outbound, room.playerIds());
        } finally {
            lock.release(roomId);
        }
        // 락 해제 후 봇 차례면 비동기로 봇 액션 트리거.
        botScheduler.scheduleBots(roomId);
        // Phase 13D — 다음 턴 타임아웃 타이머 (re)스케줄 (turnSeconds=0 이면 no-op).
        turnTimeout.onTurnAdvanced(roomId);
    }

    /**
     * 원본 payload 를 이 게임의 액션으로 역직렬화. 실패 시 본인에게 ERROR 를 보내고 null.
     *
     * <p>하드타입 {@code @Payload} 시절에는 알 수 없는 `@action` 이 프레임워크 변환 단계에서
     * 터져 클라가 아무 응답도 못 받았다. 이제 정상 ERROR 로 응답한다.
     */
    private GameAction parseAction(GameEngine engine, Map<String, Object> payload, String gameType,
                                   String roomId, AuthPrincipal me) {
        GameAction action = null;
        String failure = (payload == null || payload.isEmpty()) ? "empty payload" : null;
        if (failure == null) {
            try {
                action = objectMapper.convertValue(payload, engine.actionType());
            } catch (IllegalArgumentException e) {
                failure = e.getMessage();
            }
        }
        if (action == null) {
            log.info("Action deserialization failed: gameType={} reason={}", gameType, failure);
            broadcaster.sendErrorTo(me.userId(), roomId, "INVALID_ACTION",
                    "Unknown or malformed action for this game");
            return null;
        }
        return action;
    }
}
