package com.mirboard.domain.lobby.auth;

import java.security.Principal;

public record AuthPrincipal(long userId, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
