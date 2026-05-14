package com.mirboard.infra.messaging;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 단일 인스턴스 또는 테스트용 in-memory {@link MessageGateway}. publish 시 직접
 * 모든 등록된 handler 를 호출 (Pub/Sub 라운드트립 없음). 패턴 매칭은 단순 prefix
 * 와 와일드카드 (suffix-{@code *} / suffix-{@code **}) 지원 — Redis 패턴 문법의
 * 부분집합.
 *
 * <p>활성화: {@code mirboard.messaging.gateway=in-memory} 또는 미설정 (default).
 */
@Component
@ConditionalOnProperty(prefix = "mirboard.messaging", name = "gateway",
        havingValue = "in-memory", matchIfMissing = true)
public class InMemoryMessageGateway implements MessageGateway {

    private final Map<String, List<BiConsumer<String, String>>> subscriptions =
            new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, String payload) {
        for (var entry : subscriptions.entrySet()) {
            if (matches(entry.getKey(), channel)) {
                for (BiConsumer<String, String> handler : entry.getValue()) {
                    handler.accept(channel, payload);
                }
            }
        }
    }

    @Override
    public void subscribe(String pattern, BiConsumer<String, String> handler) {
        subscriptions.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Redis psubscribe 의 단순화 — {@code *} 는 임의 문자열 (구분자 포함). 정확
     * 매칭도 지원. {@code stomp:topic:room:*} → {@code stomp:topic:room:abc} 매치.
     */
    private static boolean matches(String pattern, String channel) {
        if (pattern.equals(channel)) return true;
        if (!pattern.contains("*")) return false;
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return channel.matches(regex);
    }
}
