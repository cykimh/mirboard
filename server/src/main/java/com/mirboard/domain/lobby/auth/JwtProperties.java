package com.mirboard.domain.lobby.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mirboard.jwt")
public record JwtProperties(String secret, Duration expiresIn, String issuer) {
}
