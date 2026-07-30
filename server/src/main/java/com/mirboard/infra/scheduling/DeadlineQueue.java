package com.mirboard.infra.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * D-96 — 인스턴스를 넘어 공유되는 지연 실행 큐.
 *
 * <p>기존 스케줄러들은 {@code ScheduledExecutorService} + in-memory future 맵이라
 * 타이머를 건 인스턴스에 묶여 있었다. 그 인스턴스가 죽으면 타이머가 사라지고
 * (탈주가 영원히 확정 안 됨), 두 인스턴스가 각자 걸면 중복 발화한다.
 *
 * <p>대신 만료 시각을 Redis ZSET 에 두고 <b>모든 인스턴스가 폴링</b>한다. 만료분 pop 은
 * Lua 로 원자화돼 한 항목이 정확히 한 인스턴스에만 간다. 인스턴스가 죽어도 ZSET 은
 * 남아 있으므로 다른 인스턴스가 자동 인계한다 — 리더 선출이 필요 없고, 리더 부재라는
 * 장애 모드도 생기지 않는다.
 *
 * <p>정밀도는 폴링 주기(기본 1s)에 좌우된다. 턴 제한(30~90s)·탈주 유예(120s) 에는
 * 충분하며, 더 짧은 타이머가 필요해지면 그때 주기를 조정한다.
 */
@Component
public class DeadlineQueue {

    /** 한 번의 폴링에서 가져올 최대 항목 수 — 한 인스턴스가 폭주분을 독점하지 않게. */
    private static final int BATCH = 64;

    private final StringRedisTemplate redis;
    private final RedisScript<List> pollScript;
    private final Clock clock;

    public DeadlineQueue(StringRedisTemplate redis,
                         @Qualifier("deadlinePollScript") RedisScript<List> pollScript,
                         Clock clock) {
        this.redis = redis;
        this.pollScript = pollScript;
        this.clock = clock;
    }

    /**
     * 데드라인 등록. 같은 {@code member} 가 이미 있으면 score 만 갱신된다(ZADD 의미) —
     * 즉 "기존 타이머 취소 후 재등록"이 자동으로 된다.
     */
    public void schedule(String kind, String member, Duration delay) {
        long dueAt = clock.millis() + Math.max(0L, delay.toMillis());
        redis.opsForZSet().add(key(kind), member, dueAt);
        // 고아 방지 — 가장 긴 데드라인보다 넉넉히.
        redis.expire(key(kind), Duration.ofHours(12));
    }

    /** 데드라인 취소. 재접속·턴 진행 등으로 더 이상 필요 없어졌을 때. */
    public void cancel(String kind, String member) {
        redis.opsForZSet().remove(key(kind), member);
    }

    /**
     * 만료분을 원자적으로 pop. 반환된 항목은 <b>이 인스턴스가 단독 소유</b>하므로
     * 호출자가 반드시 처리해야 한다(다시 큐에 없음).
     */
    @SuppressWarnings("unchecked")
    public List<String> pollDue(String kind) {
        List<?> due = redis.execute(pollScript, List.of(key(kind)),
                Long.toString(clock.millis()), Integer.toString(BATCH));
        if (due == null || due.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<String>) due.stream().map(String::valueOf).toList();
    }

    static String key(String kind) {
        return "deadlines:" + kind;
    }
}
