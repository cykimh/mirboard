package com.mirboard.infra.ws;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.bot.GameProgressKick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * S5 — 게임 토픽({@code /topic/room/{id}}) 구독은 클라가 (재)접속해 판을 다시 보기 시작한 순간이다 — 배포 뒤에는 모든
 * 클라가 다시 붙으며 이 구독을 보낸다. 그때 진행 킥을 건다. 같은 세션이 함께 구독하는 대기실 메타·채팅·리액션
 * 토픽에는 걸지 않는다(한 접속에 한 번이면 된다).
 */
@ExtendWith(MockitoExtension.class)
class WsSessionLifecycleListenerTest {

    @Mock
    private RoomPresence presence;

    @Mock
    private RoomDisconnectHandler disconnects;

    @Mock
    private GameProgressKick kick;

    private WsSessionLifecycleListener listener;

    private static SessionSubscribeEvent subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("ws-1");
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(new Object(), message, new AuthPrincipal(11L, "tester"));
    }

    @Test
    void subscribing_the_game_topic_kicks_the_room() {
        listener = new WsSessionLifecycleListener(presence, disconnects, kick);
        listener.onSubscribe(subscribe("/topic/room/r1"));

        verify(presence).join("ws-1", 11L, "r1");
        // 구독자를 넘긴다 — 킥은 참가자·관전자의 것만 받는다(GameProgressKickTest).
        verify(kick).kick("r1", 11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/room/r1/meta", "/topic/room/r1/chat", "/topic/room/r1/reaction",
            "/topic/lobby/chat"})
    void other_topics_do_not_kick(String destination) {
        listener = new WsSessionLifecycleListener(presence, disconnects, kick);
        listener.onSubscribe(subscribe(destination));

        verify(kick, never()).kick(anyString(), anyLong());
    }
}
