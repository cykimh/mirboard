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
    private final SuspensionService suspensions;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                       LoginAttemptService loginAttempts, SuspensionService suspensions) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.loginAttempts = loginAttempts;
        this.suspensions = suspensions;
    }

    @Transactional
    public RegisteredUser register(String rawUsername, String password) {
        String username = UsernamePolicy.normalize(rawUsername);
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
    public AuthenticatedUser authenticate(String rawUsername, String password) {
        String username = UsernamePolicy.normalize(rawUsername);
        // D-84 — 실패 누적 잠금 검사 → 인증 → 결과에 따라 카운터 갱신.
        loginAttempts.assertNotLocked(username);
        var user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttempts.onFailure(username);
            throw new InvalidCredentialsException();
        }
        // D-86 — 자격증명이 맞아도 정지된 계정은 로그인 불가.
        if (suspensions.isSuspended(user.getId())) {
            throw new AccountSuspendedException();
        }
        loginAttempts.onSuccess(username);
        return new AuthenticatedUser(user.getId(), user.getUsername());
    }

    /**
     * D-85 — 본인 비밀번호 변경. 새 비밀번호 정책 검증 → BCrypt 재해시. D-304 — 현재 비밀번호는 묻지 않는다
     * (로그인한 본인의 userId 로만 불린다 — 열린 기기·탈취된 토큰으로도 바꿀 수 있다는 점을 받아들였다).
     * 정책 위반은 {@link InvalidPasswordException}(400), 게스트는 {@link GuestForbiddenException}(403, D-117).
     * users 스키마 변경 없음(password_hash 갱신만).
     */
    @Transactional
    public void changePassword(long userId, String newPassword) {
        var user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        // D-117 — 게스트는 비밀번호가 없다. sentinel 불일치(401)로 우연히 막히던 것을 정책으로
        // 명시한다 — 403 GUEST_FORBIDDEN.
        if (GuestPolicy.isGuest(user.getUsername())) {
            throw new GuestForbiddenException();
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
