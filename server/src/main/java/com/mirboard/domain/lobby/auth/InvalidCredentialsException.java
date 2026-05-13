package com.mirboard.domain.lobby.auth;

public final class InvalidCredentialsException extends AuthException {
    public InvalidCredentialsException() {
        super("Bad credentials");
    }
}
