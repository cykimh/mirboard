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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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
        var user = userRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthenticatedUser(user.getId(), user.getUsername());
    }

    public record RegisteredUser(long userId, String username) {
    }

    public record AuthenticatedUser(long userId, String username) {
    }
}
