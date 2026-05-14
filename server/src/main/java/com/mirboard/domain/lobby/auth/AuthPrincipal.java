package com.mirboard.domain.lobby.auth;

import java.security.Principal;

public record AuthPrincipal(long userId, String username) implements Principal {

    /**
     * STOMP 의 user destination 라우팅(`convertAndSendToUser`) 이 본 값을 사용한다.
     * 사용자별 큐로 정확히 보내기 위해 안정적이고 단조 증가하는 {@code userId} 문자열을
     * 채택 — username 은 변경 가능성이 있어 라우팅 키로 부적합.
     */
    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
