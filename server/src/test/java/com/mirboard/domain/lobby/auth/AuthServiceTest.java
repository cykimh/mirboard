package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-13T00:00:00Z"), ZoneOffset.UTC);

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = Mockito.mock(PasswordEncoder.class);
        service = new AuthService(userRepository, passwordEncoder, CLOCK);
    }

    @Test
    void register_rejects_invalid_username() {
        assertThatThrownBy(() -> service.register("x", "validpass1"))
                .isInstanceOf(InvalidUsernameException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_rejects_short_password() {
        assertThatThrownBy(() -> service.register("alice_01", "short"))
                .isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void register_rejects_taken_username() {
        given(userRepository.existsByUsername("alice_01")).willReturn(true);

        assertThatThrownBy(() -> service.register("alice_01", "validpass1"))
                .isInstanceOf(UsernameTakenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_persists_user_with_hashed_password() {
        given(userRepository.existsByUsername("alice_01")).willReturn(false);
        given(passwordEncoder.encode("validpass1")).willReturn("hashed");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        var result = service.register("alice_01", "validpass1");

        assertThat(result.username()).isEqualTo("alice_01");
        verify(userRepository).save(Mockito.argThat(u ->
                "alice_01".equals(u.getUsername())
                        && "hashed".equals(u.getPasswordHash())
                        && u.getWinCount() == 0
                        && u.getLoseCount() == 0));
    }

    @Test
    void authenticate_returns_principal_on_match() {
        var stored = User.create("bob", "stored-hash", CLOCK);
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(stored));
        given(passwordEncoder.matches("pw12345678", "stored-hash")).willReturn(true);

        var result = service.authenticate("bob", "pw12345678");

        assertThat(result.username()).isEqualTo("bob");
    }

    @Test
    void authenticate_fails_when_user_missing() {
        given(userRepository.findByUsername(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("ghost", "whatever1"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_fails_on_password_mismatch() {
        var stored = User.create("bob", "stored-hash", CLOCK);
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(stored));
        given(passwordEncoder.matches(eq("wrong-pass"), eq("stored-hash"))).willReturn(false);

        assertThatThrownBy(() -> service.authenticate("bob", "wrong-pass"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
