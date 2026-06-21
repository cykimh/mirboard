package com.mirboard.domain.lobby.auth;

/**
 * D-86 — 어드민이 정지한 계정의 로그인/접속 시도. 403 ACCOUNT_SUSPENDED 로 매핑.
 * (brute-force 잠금 {@link AccountLockedException}(423)과 구분.)
 */
public class AccountSuspendedException extends RuntimeException {
    public AccountSuspendedException() {
        super("Account suspended");
    }
}
