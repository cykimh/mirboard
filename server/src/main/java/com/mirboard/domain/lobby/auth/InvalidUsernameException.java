package com.mirboard.domain.lobby.auth;

public final class InvalidUsernameException extends AuthException {
    private final String username;

    public InvalidUsernameException(String username) {
        super("Invalid username: " + username);
        this.username = username;
    }

    public String username() {
        return username;
    }
}
