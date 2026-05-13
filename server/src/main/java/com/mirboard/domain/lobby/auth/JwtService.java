package com.mirboard.domain.lobby.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String CLAIM_USERNAME = "username";

    private final SecretKey signingKey;
    private final JwtProperties props;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "mirboard.jwt.secret must be at least 32 bytes for HS256, got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issue(long userId, String username) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(props.expiresIn());
        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(Long.toString(userId))
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, exp);
    }

    public AuthPrincipal parse(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String username = claims.get(CLAIM_USERNAME, String.class);
            return new AuthPrincipal(userId, username);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidCredentialsException();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
