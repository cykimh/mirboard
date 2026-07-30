package com.mirboard.infra.ratelimit;

import java.time.Duration;

/**
 * 레이트리밋 버킷 하나의 파라미터 — {@code window} 동안 subject 당 최대 {@code limit} 회.
 *
 * <p>D-90. {@code limit <= 0} 이면 해당 버킷은 <b>비활성</b>(무제한)으로 취급한다 —
 * 운영 중 특정 버킷만 설정으로 끌 수 있게 하기 위한 탈출구.
 *
 * @param name   버킷 이름. Redis 키 네임스페이스(`ratelimit:{name}:{subject}`)가 되므로
 *               설정 키와 동일하게 유지한다.
 * @param limit  윈도당 최대 허용 횟수. 0 이하면 무제한.
 * @param window 고정 윈도 길이. 최소 1초로 clamp 된다(Lua EXPIRE 가 초 단위).
 */
public record RateLimitPolicy(String name, int limit, Duration window) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rate limit policy name is required");
        }
        if (window == null) {
            window = Duration.ofMinutes(1);
        }
    }

    /** 무제한(비활성) 버킷인지. */
    public boolean unlimited() {
        return limit <= 0;
    }

    /** Lua EXPIRE 인자용 초. 0초 윈도는 EXPIRE 가 즉시 만료시켜 사실상 무제한이 되므로 최소 1. */
    public long windowSeconds() {
        return Math.max(1L, window.toSeconds());
    }
}
