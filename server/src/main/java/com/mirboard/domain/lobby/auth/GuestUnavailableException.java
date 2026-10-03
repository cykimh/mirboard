package com.mirboard.domain.lobby.auth;

/**
 * D-117 — 오늘(UTC) 전역 게스트 상한을 다 썼거나, 상한을 판정할 수 없다(Redis 장애 —
 * fail-closed). 503 GUEST_UNAVAILABLE, {@code Retry-After} 는 다음 UTC 자정까지 남은 초.
 */
public class GuestUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public GuestUnavailableException(long retryAfterSeconds) {
        super("Guest accounts are temporarily unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
