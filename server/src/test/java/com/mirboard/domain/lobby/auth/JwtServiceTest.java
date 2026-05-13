package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-must-be-32-bytes-or-more-padding-1234567890";
    private static final String ISSUER = "mirboard-test";

    private final Instant fixedNow = Instant.parse("2026-05-13T00:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
    private final JwtProperties props = new JwtProperties(SECRET, Duration.ofMinutes(30), ISSUER);
    private final JwtService service = new JwtService(props, clock);

    @Test
    void issue_then_parse_round_trips_claims() {
        var issued = service.issue(42L, "alice_01");

        var principal = service.parse(issued.token());

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.username()).isEqualTo("alice_01");
        assertThat(issued.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofMinutes(30)));
    }

    @Test
    void parse_rejects_token_signed_with_other_secret() {
        var other = new JwtService(
                new JwtProperties("different-secret-also-32-bytes-or-more-pad-xx", Duration.ofMinutes(30), ISSUER),
                clock);
        var fromOther = other.issue(1L, "x").token();

        assertThatThrownBy(() -> service.parse(fromOther))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void parse_rejects_expired_token() {
        var issued = service.issue(7L, "bob");

        // Move the clock forward beyond expiry.
        var laterClock = Clock.fixed(fixedNow.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        var laterService = new JwtService(props, laterClock);

        assertThatThrownBy(() -> laterService.parse(issued.token()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void parse_rejects_token_with_wrong_issuer() {
        var foreign = new JwtService(
                new JwtProperties(SECRET, Duration.ofMinutes(30), "someone-else"),
                clock);
        var foreignToken = foreign.issue(1L, "x").token();

        assertThatThrownBy(() -> service.parse(foreignToken))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void constructor_rejects_too_short_secret() {
        var weak = new JwtProperties("too-short", Duration.ofMinutes(30), ISSUER);
        assertThatThrownBy(() -> new JwtService(weak, clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
