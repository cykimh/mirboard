package com.mirboard.domain.lobby.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * D-84 — 로그인 실패 누적 잠금 (Redis 전용, users 비침범).
 *
 * <p>{@code login:fail:{username}} 카운터를 윈도 TTL 로 누적하고, {@code maxFailures} 이상이면
 * {@code lock:login:{username}} 를 {@code lockDuration} TTL 로 설정한다. 성공 시 둘 다 삭제.
 * 잠금/카운터는 전부 휘발(Redis)이라 스키마 변경이 없다(D-02 화이트리스트 불변).
 */
@Service
public class LoginAttemptService {

    private final StringRedisTemplate redis;
    private final AuthLockoutProperties props;

    public LoginAttemptService(StringRedisTemplate redis, AuthLockoutProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void assertNotLocked(String username) {
        if (Boolean.TRUE.equals(redis.hasKey(lockKey(username)))) {
            throw new AccountLockedException(username);
        }
    }

    public void onFailure(String username) {
        String failKey = failKey(username);
        Long count = redis.opsForValue().increment(failKey);
        if (count == null) return;
        if (count == 1L) {
            redis.expire(failKey, props.window());
        }
        if (count >= props.maxFailures()) {
            redis.opsForValue().set(lockKey(username), "1", props.lockDuration());
        }
    }

    public void onSuccess(String username) {
        redis.delete(failKey(username));
        redis.delete(lockKey(username));
    }

    private static String failKey(String username) {
        return "login:fail:" + username;
    }

    private static String lockKey(String username) {
        return "lock:login:" + username;
    }
}
