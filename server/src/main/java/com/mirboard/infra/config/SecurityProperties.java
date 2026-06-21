package com.mirboard.infra.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-83 — CORS origin 화이트리스트. 전면 개방(`*`) 폐지. dev=localhost, prod=실도메인(env).
 */
@ConfigurationProperties("mirboard.security")
public record SecurityProperties(List<String> allowedOrigins) {

    public SecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
