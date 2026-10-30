package com.mirboard.domain.lobby.auth;

/** D-156 — 길이만 본다: 4~64자면 무엇이든(사용자 결정 — 문자 종류 규칙 없음). */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new InvalidPasswordException();
        }
    }
}
