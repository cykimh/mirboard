package com.mirboard.domain.lobby.auth;

public sealed class AuthException extends RuntimeException
        permits InvalidUsernameException,
                InvalidPasswordException,
                UsernameTakenException,
                InvalidCredentialsException {

    protected AuthException(String message) {
        super(message);
    }
}
