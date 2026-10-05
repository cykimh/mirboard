package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.SuspensionService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * D-126 — 비공개 큐를 배달하려고 브로커에 `/queue` 를 열었으므로, 클라가 사용자 목적지
 * 변환을 거치지 않고 `/queue/...` 를 <b>직접</b> 구독하는 길은 막는다. 열어 두면 남의 세션 id 를
 * 아는 클라가 `/queue/room/{id}-user{세션}` 을 구독해 손패를 볼 수 있다(State Hiding).
 * 클라는 늘 `/user/queue/...` 로 구독하고, 그건 Spring 이 본인 세션 목적지로 바꿔 준다.
 */
class StompAuthChannelInterceptorTest {

    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(
            mock(JwtService.class), mock(SuspensionService.class));
    private final MessageChannel channel = mock(MessageChannel.class);

    private static Message<byte[]> subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void direct_subscription_to_a_raw_queue_is_refused() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/queue/room/r1-userabc123"), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void user_queue_and_topic_subscriptions_pass() {
        assertThat(interceptor.preSend(subscribe("/user/queue/room/r1"), channel)).isNotNull();
        assertThat(interceptor.preSend(subscribe("/topic/room/r1"), channel)).isNotNull();
    }
}
