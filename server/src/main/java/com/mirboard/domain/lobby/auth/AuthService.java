package com.mirboard.domain.lobby.auth;

import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final LoginAttemptService loginAttempts;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                       LoginAttemptService loginAttempts) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.loginAttempts = loginAttempts;
    }

    @Transactional
    public RegisteredUser register(String username, String password) {
        UsernamePolicy.validate(username);
        PasswordPolicy.validate(password);
        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException(username);
        }
        var user = User.create(username, passwordEncoder.encode(password), clock);
        var saved = userRepository.save(user);
        return new RegisteredUser(saved.getId(), saved.getUsername());
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(String username, String password) {
        // D-84 — 실패 누적 잠금 검사 → 인증 → 결과에 따라 카운터 갱신.
        loginAttempts.assertNotLocked(username);
        var user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttempts.onFailure(username);
            throw new InvalidCredentialsException();
        }
        loginAttempts.onSuccess(username);
        return new AuthenticatedUser(user.getId(), user.getUsername());
    }

    /**
     * D-85 — 본인 비밀번호 변경. 현재 비밀번호 재검증 → 새 비밀번호 정책 검증 → BCrypt 재해시.
     * 현재 비번 불일치는 {@link InvalidCredentialsException}(401), 정책 위반은
     * {@link InvalidPasswordException}(400). users 스키마 변경 없음(password_hash 갱신만).
     */
    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        var user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        PasswordPolicy.validate(newPassword);
        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public record RegisteredUser(long userId, String username) {
    }

    public record AuthenticatedUser(long userId, String username) {
    }
}
