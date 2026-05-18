package com.mirboard.infra.ws;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 19(#3, D-75) — IN_GAME 중 WS 끊김 시 "재접속 유예". 끊김 즉시
 * 탈주로 확정하지 않고 {@code grace-seconds} 동안 기다린 뒤, 그때까지
 * 같은 방의 라이브 세션이 없으면 {@link DesertionService} 로 탈주를 확정한다.
 *
 * <p>구조는 {@link com.mirboard.infra.bot.TurnTimeoutScheduler} 와 동일한
 * 지연 스케줄 패턴 — {@link ScheduledExecutorService} daemon + per-(room,user)
 * future 맵 + {@code @PreDestroy shutdownNow}. 단일 머신 배포 전제(D-03)라
 * future 맵이 in-memory. 다중 인스턴스 전환 시 교체 필요(범위 밖).
 */
@Component
public class DesertionGraceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DesertionGraceScheduler.class);

    private final WsSessionRegistry registry;
    private final DesertionService desertion;
    private final long graceSeconds;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public DesertionGraceScheduler(
            WsSessionRegistry registry,
            DesertionService desertion,
            @Value("${mirboard.desertion.grace-seconds:30}") long graceSeconds) {
        this.registry = registry;
        this.desertion = desertion;
        this.graceSeconds = graceSeconds;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "mirboard-desertion-grace");
            t.setDaemon(true);
            return t;
        });
    }

    /** IN_GAME 끊김 시 호출. 동일 (room,user) 의 기존 유예는 취소 후 재등록. */
    public void scheduleGrace(String roomId, long userId) {
        String key = key(roomId, userId);
        cancel(key);
        ScheduledFuture<?> f = scheduler.schedule(
                () -> fire(roomId, userId), graceSeconds, TimeUnit.SECONDS);
        futures.put(key, f);
        log.info("Desertion grace scheduled: roomId={} userId={} graceSec={}",
                roomId, userId, graceSeconds);
    }

    private void fire(String roomId, long userId) {
        futures.remove(key(roomId, userId));
        if (registry.hasLiveSession(userId, roomId)) {
            log.info("Desertion grace: 재접속 확인 — abort. roomId={} userId={}",
                    roomId, userId);
            return;
        }
        desertion.processDesertion(roomId, userId);
    }

    private void cancel(String key) {
        ScheduledFuture<?> prev = futures.remove(key);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    private static String key(String roomId, long userId) {
        return roomId + ":" + userId;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
