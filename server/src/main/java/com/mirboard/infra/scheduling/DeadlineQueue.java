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
 * <p>정밀도는 폴링 주기(기본 250ms, {@code mirboard.scheduling.poll-interval-millis})에 좌우된다.
 * 턴 제한(30~90s)·탈주 유예(120s) 에는 충분하다. 다만 엔진 타이머({@code game} 종류, D-128)는
 * 수백 ms~수 초짜리라 이 해상도 — 그리고 폴러가 한 스레드로 종류를 차례로 처리해서 생기는 지연 — 를
 * 그대로 받는다. 더 정밀한 타이머가 필요해지면 그때 주기를 조정한다.
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
     * 취소하되 <b>실제로 대기 중이었는지</b>를 반환한다. `ZREM` 의 제거 개수가 곧
     * in-memory 시절의 "future 가 null 이 아니었나"와 같은 의미다 — 탈주 유예처럼
     * "정말 끊겼다가 돌아온 경우에만 알림"을 구분해야 하는 곳에서 쓴다.
     */
    public boolean cancelExisting(String kind, String member) {
        Long removed = redis.opsForZSet().remove(key(kind), member);
        return removed != null && removed > 0;
    }

    /**
     * D-130 — 그 항목이 큐에 없을 때만 건다(`ZADD NX`). 더했으면 true. 이미 걸린 무장(정상 무장·락 경합 재시도·미만기 재무장)을
     * 덮지 않는다 — 진행 킥이 "사라졌나"를 따로 읽고 쓰면, 그 사이 걸린 정상 무장을 킥이 락 없이 읽은 낡은 남은 시간으로
     * 덮을 수 있었다. pop 된 항목·취소된 항목은 없는 것이다.
     */
    public boolean scheduleIfAbsent(String kind, String member, Duration delay) {
        long dueAt = clock.millis() + Math.max(0L, delay.toMillis());
        Boolean added = redis.opsForZSet().addIfAbsent(key(kind), member, dueAt);
        redis.expire(key(kind), Duration.ofHours(12));
        return Boolean.TRUE.equals(added);
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
