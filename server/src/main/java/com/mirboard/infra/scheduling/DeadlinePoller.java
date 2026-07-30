package com.mirboard.infra.scheduling;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * D-96 — 만료된 데드라인을 주기적으로 pop 해 핸들러로 넘긴다.
 *
 * <p><b>모든 인스턴스가 동시에 폴링한다.</b> 리더를 뽑지 않는 이유는 리더가 죽는 순간
 * 모든 타이머가 멈추고, 선출 자체가 새로운 장애 모드이기 때문. 중복 실행은 리더십이
 * 아니라 {@link DeadlineQueue#pollDue} 의 <b>원자 pop</b> 으로 막는다 — 한 항목은
 * 정확히 한 인스턴스에만 간다.
 *
 * <p>인스턴스가 죽어도 ZSET 은 Redis 에 남아 있으므로 다른 인스턴스가 다음 폴링에서
 * 그대로 인계한다. 이것이 in-memory {@code ScheduledFuture} 와의 결정적 차이다.
 */
@Component
public class DeadlinePoller {

    private static final Logger log = LoggerFactory.getLogger(DeadlinePoller.class);

    private final DeadlineQueue queue;
    private final Map<String, DeadlineHandler> handlers;
    private final ScheduledExecutorService poller;
    private final long intervalMillis;

    public DeadlinePoller(DeadlineQueue queue,
                          List<DeadlineHandler> handlerBeans,
                          @Value("${mirboard.scheduling.poll-interval-millis:1000}") long intervalMillis) {
        this.queue = queue;
        this.handlers = handlerBeans.stream()
                .collect(Collectors.toMap(DeadlineHandler::kind, Function.identity()));
        this.intervalMillis = intervalMillis;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mirboard-deadline-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 빈 생성 직후 시작. {@code fixedDelay} 라 한 사이클이 늦어져도 겹쳐 돌지 않는다
     * (겹치면 같은 항목을 두 스레드가 처리하려 들 수 있다 — pop 이 막아주긴 하지만
     * 불필요한 경합을 만들 이유가 없다).
     */
    @jakarta.annotation.PostConstruct
    void start() {
        if (handlers.isEmpty()) {
            log.info("등록된 DeadlineHandler 없음 — 폴러 미기동");
            return;
        }
        poller.scheduleWithFixedDelay(this::pollOnce, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        log.info("데드라인 폴러 기동: kinds={} intervalMs={}", handlers.keySet(), intervalMillis);
    }

    /** 한 사이클. 테스트에서 주기를 기다리지 않고 직접 부를 수 있게 package-private. */
    void pollOnce() {
        for (var entry : handlers.entrySet()) {
            String kind = entry.getKey();
            DeadlineHandler handler = entry.getValue();
            try {
                for (String member : queue.pollDue(kind)) {
                    try {
                        handler.handle(member);
                    } catch (RuntimeException e) {
                        // 한 항목의 실패가 나머지를 막지 않게. 재시도는 핸들러 책임.
                        log.error("데드라인 처리 실패: kind={} member={} err={}",
                                kind, member, e.toString(), e);
                    }
                }
            } catch (RuntimeException e) {
                // Redis 장애 등 — 다음 사이클에 재시도.
                log.warn("데드라인 폴링 실패(다음 주기 재시도): kind={} err={}", kind, e.toString());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }
}
