package com.mirboard.infra.rest.auth;

import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.GuestAccountService;
import com.mirboard.domain.lobby.auth.JwtService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final GuestAccountService guestAccounts;

    public AuthController(AuthService authService, JwtService jwtService,
                          GuestAccountService guestAccounts) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.guestAccounts = guestAccounts;
    }

    // D-90 — IP 단위 레이트리밋은 컨트롤러에서 빠지고 `HttpRateLimitFilter` 의
    // `auth` 버킷이 전담한다(신규 인증 엔드포인트가 추가돼도 자동 적용).

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
                new UserDto(authenticated.userId(), authenticated.username(), false));
    }

    /**
     * D-117 — 가입 없는 체험. 방문자마다 일회용 게스트 행을 만들고 바로 로그인 응답을 준다.
     *
     * <p>본문은 쓰지 않지만 {@code consumes=JSON} 을 요구한다. 그래야 다른 사이트가 방문자
     * 브라우저로 보내는 simple request(text/plain 폼 전송)가 415 가 되고, 크로스사이트 요청은
     * 프리플라이트를 거쳐 CORS 화이트리스트(D-83)에서 403 이 된다 — 이중 방어.
     * IP 당 한도는 레이트리밋 `guest` 버킷(필터)이 맡는다.
     */
    @PostMapping(value = "/guest", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse guest() {
        var guest = guestAccounts.createGuest();
        var issued = jwtService.issue(guest.userId(), guest.username());
        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt().toEpochMilli(),
                new UserDto(guest.userId(), guest.username(), true));
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RegisterResponse(long userId, String username) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresAt, UserDto user) {
    }

    /** {@code guest} — D-117. 클라가 게스트 전용 UI(배지·제한 안내)를 고르는 근거. */
    public record UserDto(long userId, String username, boolean guest) {
    }
}
