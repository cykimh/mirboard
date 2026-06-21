package com.mirboard.domain.lobby.auth;

/**
 * D-84 — 로그인 실패 누적으로 일시 잠긴 계정. 잠금 상태는 Redis(휘발)에만 둔다(users 비침범).
 * {@code AuthException}(sealed, 자격증명 검증 계열)과 별개의 운영 보호 예외.
 */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String username) {
        super("Account temporarily locked: " + username);
    }
}
