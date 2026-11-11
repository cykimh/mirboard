package com.mirboard.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameNotFoundException;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * D-229 — 상태를 바꾸지 않는 미리보기 프레임(`/app/room/{roomId}/preview`)을 포트 ⑧ {@link GameEngine#preview} 로 넘긴다. 게임을 모른다
 * — 방 → {@link GameEngineProvider#forRoom} → {@link GameEngine#actionType()} 으로 역직렬화, 게임이 걸러 준 이벤트만 방 토픽으로 낸다.
 *
 * <p>{@link GameStompController} 와 달리 <b>하지 않는 것</b>: 방 액션 락·상태 저장·턴 데드라인 재무장·봇 스케줄·거절 메트릭·ERROR 응답.
 * 그래서 미리보기는 {@code BUSY} 를 만들지도 받지도 않고 턴 시계를 건드리지 않는다. 받아들일 수 없는 프레임(진행 중 아님·좌석 없음·관전자·
 * 역직렬화 실패·게임이 empty)은 <b>조용히 버린다</b> — 초당 수 번 오는 프레임에 에러를 되쏘면 그 자체가 증폭이다.
 *
 * <p>게임이 계약을 어긴 이벤트(비공개이거나 순번을 쓰는 것)는 WARN 후 버린다 — 순번 구멍(D-126)·비공개 누출(D-01) 방지.
 */
@Controller
public class GamePreviewController {

    private static final Logger log = LoggerFactory.getLogger(GamePreviewController.class);

    private final RoomService roomService;
    private final GameEngineProvider engines;
    private final ObjectMapper objectMapper;
    private final GameEventBroadcaster broadcaster;

    public GamePreviewController(RoomService roomService,
                                 GameEngineProvider engines,
                                 ObjectMapper objectMapper,
                                 GameEventBroadcaster broadcaster) {
        this.roomService = roomService;
        this.engines = engines;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
    }

    /** payload 를 {@code Map} 으로 받는 이유는 {@link GameStompController#onAction} 과 같다(게임별 타입·Jackson 버전 중립). */
    @MessageMapping("/room/{roomId}/preview")
    public void onPreview(@DestinationVariable String roomId,
                          @Payload Map<String, Object> payload,
                          Principal principal) {
        if (!(principal instanceof AuthPrincipal me) || payload == null || payload.isEmpty()) {
            return;
        }
        Room room;
        try {
            room = roomService.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            return;
        }
        int seat = room.playerIds().indexOf(me.userId());
        if (seat < 0 || room.status() != RoomStatus.IN_GAME) {
            return;
        }
        GameEngine engine;
        try {
            engine = engines.forRoom(room);
        } catch (GameNotFoundException e) {
            return;
        }
        GameAction input;
        try {
            input = objectMapper.convertValue(payload, engine.actionType());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (input == null) {
            return;
        }
        Optional<GameEvent> event;
        try {
            // 락 없이 읽는다 — 같은 순간의 액션과 겹쳐 낡은 상태를 볼 수 있다(계약 1, 받는 쪽이 낡은 프레임을 거른다).
            GameState state = engine.loadState().orElse(null);
            if (state == null) {
                return;
            }
            event = engine.preview(state, seat, input);
        } catch (RuntimeException e) {
            log.warn("Preview failed: roomId={} gameType={} err={}", roomId, room.gameType(), e.toString());
            return;
        }
        if (event.isEmpty()) {
            return;
        }
        GameEvent ev = event.get();
        if (ev.privateSeat() >= 0 || ev.sequenced()) {
            log.warn("Preview event breaks the port contract (must be public and unsequenced), dropped: "
                            + "roomId={} gameType={} type={} privateSeat={} sequenced={}",
                    roomId, room.gameType(), ev.envelopeType(), ev.privateSeat(), ev.sequenced());
            return;
        }
        broadcaster.broadcast(roomId, List.of(ev), room.playerIds());
    }
}
