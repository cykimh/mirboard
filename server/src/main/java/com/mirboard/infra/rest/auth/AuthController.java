package com.mirboard.infra.rest.auth;

import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.infra.ratelimit.AuthRateLimiter;
import com.mirboard.infra.web.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthService authService, JwtService jwtService, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody RegisterRequest req, HttpServletRequest http) {
        rateLimit(http);
        var registered = authService.register(req.username(), req.password());
        return new RegisterResponse(registered.userId(), registered.username());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req, HttpServletRequest http) {
        rateLimit(http);
        var authenticated = authService.authenticate(req.username(), req.password());
        var issued = jwtService.issue(authenticated.userId(), authenticated.username());
        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt().toEpochMilli(),
                new UserDto(authenticated.userId(), authenticated.username()));
    }

    /** D-84 — 클라이언트 IP(프록시 뒤 X-Forwarded-* 는 forward-headers-strategy=framework 적용) 단위 레이트리밋. */
    private void rateLimit(HttpServletRequest http) {
        if (!rateLimiter.tryAcquire(http.getRemoteAddr())) {
            throw new TooManyRequestsException();
        }
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RegisterResponse(long userId, String username) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresAt, UserDto user) {
    }

    public record UserDto(long userId, String username) {
    }
}
