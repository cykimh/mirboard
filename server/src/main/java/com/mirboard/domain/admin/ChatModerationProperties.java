package com.mirboard.domain.admin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** D-86 — 채팅 금칙어 목록(운영자 설정). 비어 있으면 마스킹 없음. */
@ConfigurationProperties("mirboard.moderation")
public record ChatModerationProperties(List<String> bannedWords) {

    public ChatModerationProperties {
        bannedWords = bannedWords == null ? List.of() : List.copyOf(bannedWords);
    }
}
