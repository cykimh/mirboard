package com.mirboard.domain.lobby.auth;

public final class UsernameTakenException extends AuthException {
    private final String username;

    public UsernameTakenException(String username) {
        super("Username already taken: " + username);
        this.username = username;
    }

    public String username() {
        return username;
    }
}
