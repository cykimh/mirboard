package com.mirboard.domain.lobby.auth;

public final class InvalidPasswordException extends AuthException {
    public InvalidPasswordException() {
        super("Password does not satisfy length policy ("
                + PasswordPolicy.MIN_LENGTH + "~" + PasswordPolicy.MAX_LENGTH + ")");
    }
}
