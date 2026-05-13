package com.mirboard.infra.rest.auth;

import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.JwtService;
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

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody RegisterRequest req) {
        var registered = authService.register(req.username(), req.password());
        return new RegisterResponse(registered.userId(), registered.username());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        var authenticated = authService.authenticate(req.username(), req.password());
        var issued = jwtService.issue(authenticated.userId(), authenticated.username());
        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt().toEpochMilli(),
                new UserDto(authenticated.userId(), authenticated.username()));
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
