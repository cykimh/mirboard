package com.mirboard.domain.lobby.auth;

import java.util.regex.Pattern;

public final class UsernamePolicy {

    public static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    private UsernamePolicy() {
    }

    public static void validate(String username) {
        if (username == null || !PATTERN.matcher(username).matches()) {
            throw new InvalidUsernameException(username);
        }
    }
}
