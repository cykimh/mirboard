package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.StompPublisher;
import java.security.Principal;
import java.time.Clock;
import java.util.Set;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 가벼운 이모지 반응(P2/7). 채팅({@link RoomChatController}) 패턴 답습 — seq 없는
 * 메타 이벤트로 `/topic/room/{id}/reaction` 에 broadcast. 참여자/관전자만 송신,
 * 허용 이모지 화이트리스트만 통과(임의 문자열·도배 방지). 발신 좌석을 함께 실어
 * 클라가 해당 좌석에 떠오르는 애니로 렌더(관전자는 좌석 -1 → 렌더 생략).
 */
@Controller
public class RoomReactionController {

    /** 허용 이모지. 클라 팔레트와 일치시킨다. */
    private static final Set<String> ALLOWED = Set.of(
            "👍", "😂", "😮", "😢", "🔥", "👏", "❤️", "🎉");

    private final Clock clock;
    private final StompPublisher publisher;
    private final RoomService roomService;

    public RoomReactionController(Clock clock, StompPublisher publisher, RoomService roomService) {
        this.clock = clock;
        this.publisher = publisher;
        this.roomService = roomService;
    }

    @MessageMapping("/room/{roomId}/reaction")
    public void handleReaction(@DestinationVariable String roomId,
                               @Payload ReactionRequest req,
                               Principal principal) {
        AuthPrincipal me = (AuthPrincipal) principal;
        if (req == null || req.emoji() == null || !ALLOWED.contains(req.emoji())) {
            return;
        }
        int seat;
        try {
            if (!roomService.isParticipantOrSpectator(roomId, me.userId())) {
                return;
            }
            seat = roomService.getRoom(roomId).playerIds().indexOf(me.userId());
        } catch (RoomNotFoundException e) {
            return;
        }
        var envelope = StompEnvelope.of(
                "REACTION",
                new ReactionMessage(seat, req.emoji()),
                clock);
        publisher.publishToTopic("/topic/room/" + roomId + "/reaction", envelope);
    }

    public record ReactionRequest(String emoji) {
    }

    public record ReactionMessage(int fromSeat, String emoji) {
    }
}
