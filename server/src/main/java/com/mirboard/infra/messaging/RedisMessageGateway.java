package com.mirboard.infra.messaging;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 기반 {@link MessageGateway}. 활성화:
 * {@code mirboard.messaging.gateway=redis}. {@code RedisMessageListenerContainer}
 * 는 별도 스레드 풀에서 메시지를 수신해 handler 를 호출 — STOMP 핸들러도 가상
 * 스레드 위에서 동작하므로 race 위험은 작지만, handler 안에서 무거운 작업은 피한다.
 */
@Component
@ConditionalOnProperty(prefix = "mirboard.messaging", name = "gateway",
        havingValue = "redis")
public class RedisMessageGateway implements MessageGateway {

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;

    public RedisMessageGateway(StringRedisTemplate redis,
                               RedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.afterPropertiesSet();
        this.container.start();
    }

    @Override
    public void publish(String channel, String payload) {
        redis.convertAndSend(channel, payload);
    }

    @Override
    public void subscribe(String pattern, BiConsumer<String, String> handler) {
        var adapter = new MessageListenerAdapter((org.springframework.data.redis.connection.MessageListener)
                (message, channelBytes) -> {
                    String channel = channelBytes == null
                            ? ""
                            : new String(channelBytes, StandardCharsets.UTF_8);
                    String payload = new String(message.getBody(), StandardCharsets.UTF_8);
                    handler.accept(channel, payload);
                });
        adapter.afterPropertiesSet();
        container.addMessageListener(adapter, new PatternTopic(pattern));
    }
}
