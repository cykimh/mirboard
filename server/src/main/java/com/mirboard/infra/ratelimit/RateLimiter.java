package com.mirboard.infra.ratelimit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * D-90 — 범용 고정 윈도 레이트리밋. 버킷 이름 + 주체(subject)로 Redis 카운터를 잡고
 * `rate_limit_fixed_window.lua`(INCR + 첫 요청에 EXPIRE, 원자)로 판정한다.
 *
 * <p>알고리즘은 D-84 그대로 고정 윈도다. 경계에서 순간적으로 한도의 2배까지 통과할 수
 * 있지만, 목적이 정밀한 쉐이핑이 아니라 스팸 차단이고 한도를 관대하게 잡아 무해하다.
 *
 * <p><b>Redis 장애 시에는 통과(fail-open)</b>시킨다 — 레이트리밋은 부가 방어이지
 * 가용성의 전제가 아니라서, Redis 가 흔들릴 때 게임 전체가 멈추는 편이 훨씬 나쁘다.
 * (인증 brute-force 방어는 별도로 `LoginAttemptService` 가 담당한다.)
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;
    private final RateLimitProperties props;

    public RateLimiter(StringRedisTemplate redis,
                       @Qualifier("rateLimitFixedWindowScript") RedisScript<Long> script,
                       RateLimitProperties props) {
        this.redis = redis;
        this.script = script;
        this.props = props;
    }

    /**
     * @param bucket  버킷 이름 ({@link RateLimitProperties} 상수)
     * @param subject {@link RateLimitSubject} 가 만든 주체 키
     * @return true 면 허용, false 면 윈도 한도 초과
     */
    public boolean tryAcquire(String bucket, String subject) {
        RateLimitPolicy policy = props.policy(bucket);
        if (policy.unlimited()) {
            return true;
        }
        try {
            Long allowed = redis.execute(script, List.of(key(bucket, subject)),
                    Integer.toString(policy.limit()), Long.toString(policy.windowSeconds()));
            return allowed == null || allowed == 1L;
        } catch (DataAccessException e) {
            // fail-open — Redis 가 죽었다고 게임까지 막지 않는다.
            log.warn("Rate limit check failed (allowing through): bucket={} err={}",
                    bucket, e.toString());
            return true;
        }
    }

    private static String key(String bucket, String subject) {
        return "ratelimit:" + bucket + ":" + subject;
    }
}
