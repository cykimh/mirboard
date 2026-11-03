package com.mirboard.infra.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 기반 {@link MessageGateway}. 활성화:
 * {@code mirboard.messaging.gateway=redis}. {@code RedisMessageListenerContainer}
 * 는 별도 스레드 풀에서 메시지를 수신해 handler 를 호출 — STOMP 핸들러도 가상
 * 스레드 위에서 동작하므로 race 위험은 작지만, handler 안에서 무거운 작업은 피한다.
 *
 * <p>D-172 — 구독 연결 하트비트. 운영 Redis 는 D-171 부터 Fly 프록시(Flycast)를 거친다. 명령 연결은 데드라인 폴링(250ms)으로
 * 늘 바쁘지만 구독 연결은 게임 이벤트가 없으면 조용해서, 프록시가 끊으면 STOMP 방송이 소리 없이 멈출 수 있다. 그래서
 * {@code heartbeat} 마다 전용 채널에 한 번 발행해 구독 연결에 트래픽을 흘리고, 세 번 연속 돌아오지 않으면 구독을 다시 붙인다
 * (TCP keepalive 는 프록시가 TCP 를 끝내므로 소용없다).
 */
@Component
@ConditionalOnProperty(prefix = "mirboard.messaging", name = "gateway",
        havingValue = "redis")
public class RedisMessageGateway implements MessageGateway, DisposableBean {

    static final String HEARTBEAT_CHANNEL = "mirboard:pubsub-heartbeat";
    private static final int MISSED_BEATS_BEFORE_RESUBSCRIBE = 3;
    private static final Logger log = LoggerFactory.getLogger(RedisMessageGateway.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final Duration heartbeat;
    private final ScheduledExecutorService heartbeatTimer;
    private final AtomicLong heartbeatsReceived = new AtomicLong();
    private volatile long lastHeartbeatNanos = System.nanoTime();

    public RedisMessageGateway(StringRedisTemplate redis,
                               RedisConnectionFactory connectionFactory,
                               @Value("${mirboard.messaging.redis-heartbeat:30s}") Duration heartbeat) {
        this.redis = redis;
        this.heartbeat = heartbeat;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.afterPropertiesSet();
        this.container.start();

        if (heartbeat.isZero() || heartbeat.isNegative()) {
            this.heartbeatTimer = null;
            return;
        }
        container.addMessageListener((MessageListener) (message, pattern) -> {
            heartbeatsReceived.incrementAndGet();
            lastHeartbeatNanos = System.nanoTime();
        }, new ChannelTopic(HEARTBEAT_CHANNEL));
        this.heartbeatTimer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("redis-pubsub-heartbeat").factory());
        long ms = heartbeat.toMillis();
        heartbeatTimer.scheduleAtFixedRate(this::beat, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void publish(String channel, String payload) {
        redis.convertAndSend(channel, payload);
    }

    @Override
    public void subscribe(String pattern, BiConsumer<String, String> handler) {
        var adapter = new MessageListenerAdapter((MessageListener)
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

    /** 하트비트 한 번 — 너무 오래 안 돌아왔으면 구독을 다시 붙이고, 이번 것을 보낸다. 실패는 다음 주기에 다시. */
    void beat() {
        try {
            long silentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastHeartbeatNanos);
            if (silentMs > heartbeat.toMillis() * MISSED_BEATS_BEFORE_RESUBSCRIBE) {
                log.warn("Redis pub/sub heartbeat silent for {}ms — resubscribing", silentMs);
                lastHeartbeatNanos = System.nanoTime(); // 다시 붙는 동안 연달아 재시작하지 않게
                container.stop();
                container.start();
            }
            redis.convertAndSend(HEARTBEAT_CHANNEL, "1");
        } catch (RuntimeException e) {
            log.warn("Redis pub/sub heartbeat failed: {}", e.getMessage());
        }
    }

    long heartbeatsReceived() {
        return heartbeatsReceived.get();
    }

    @Override
    public void destroy() throws Exception {
        if (heartbeatTimer != null) heartbeatTimer.shutdownNow();
        container.destroy();
    }
}
