package com.mirboard.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.JwtProperties;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.infra.web.MdcKeys;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * D-170 — 허브 서비스 토큰 분기. 필터 체인이 실제로 호출되는 시점의 인증·MDC 를 캡처해 검증한다
 * (Docker 불필요).
 */
class JwtAuthFilterServiceTokenTest {

    private static final String SECRET = "test-secret-must-be-32-bytes-or-more-padding-1234567890";
    private static final String SERVICE_TOKEN = "hub-service-token-for-unit-test-0123456789";
    private static final long SERVICE_USER_ID = 777L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
    private final JwtService jwtService =
            new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(30), "mirboard-test"), clock);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** 체인이 불린 시점의 인증과 MDC userId. */
    private record Seen(Authentication auth, String mdcUserId, boolean chainCalled) {
        AuthPrincipal principal() {
            return auth == null ? null : (AuthPrincipal) auth.getPrincipal();
        }
    }

    private Seen run(AdminServiceProperties props, String uri, String authorization) throws Exception {
        return run(props, new MockHttpServletRequest("GET", uri), authorization);
    }

    private Seen run(AdminServiceProperties props, MockHttpServletRequest req, String authorization)
            throws Exception {
        SecurityContextHolder.clearContext();
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        AtomicReference<Seen> seen = new AtomicReference<>(new Seen(null, null, false));
        FilterChain chain = (rq, rs) -> seen.set(new Seen(
                SecurityContextHolder.getContext().getAuthentication(),
                MDC.get(MdcKeys.USER_ID), true));
        new JwtAuthFilter(jwtService, props).doFilter(req, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    private static AdminServiceProperties enabled() {
        return new AdminServiceProperties(SERVICE_TOKEN, SERVICE_USER_ID);
    }

    @Test
    void correct_token_on_admin_path_authenticates_as_service_user() throws Exception {
        Seen seen = run(enabled(), "/api/admin/chat-reports", "Bearer " + SERVICE_TOKEN);

        assertThat(seen.chainCalled()).isTrue();
        assertThat(seen.principal()).isEqualTo(new AuthPrincipal(SERVICE_USER_ID, "hub-service"));
        assertThat(seen.mdcUserId()).isEqualTo(Long.toString(SERVICE_USER_ID));
    }

    @Test
    void wrong_token_on_admin_path_is_unauthenticated() throws Exception {
        Seen seen = run(enabled(), "/api/admin/chat-reports", "Bearer " + SERVICE_TOKEN + "x");

        assertThat(seen.chainCalled()).isTrue();
        assertThat(seen.auth()).isNull();
        assertThat(seen.mdcUserId()).isNull();
    }

    @Test
    void correct_token_on_non_admin_paths_is_unauthenticated() throws Exception {
        for (String uri : new String[] {
                "/api/rooms", "/api/adminx/chat-reports", "/api/admin", "/api/me",
                "/x/api/admin/chat-reports", "/ws/info"}) {
            Seen seen = run(enabled(), uri, "Bearer " + SERVICE_TOKEN);
            assertThat(seen.auth()).as(uri).isNull();
        }
    }

    @Test
    void path_traversal_out_of_admin_is_not_authenticated() throws Exception {
        // 정규화하면 /api/rooms 인 경로 — 접두만 보고 인증하면 안 된다.
        Seen seen = run(enabled(), "/api/admin/../rooms", "Bearer " + SERVICE_TOKEN);
        assertThat(seen.auth()).isNull();
    }

    @Test
    void encoded_admin_prefix_is_judged_on_the_decoded_path() throws Exception {
        // MVC·Security 가 같은 컨트롤러로 보내는 인코딩 변형 — 디코딩 경로 기준이라 인증된다.
        Seen seen = run(enabled(), "/api/%61dmin/chat-reports", "Bearer " + SERVICE_TOKEN);
        assertThat(seen.principal()).isEqualTo(new AuthPrincipal(SERVICE_USER_ID, "hub-service"));
        // 반대로 디코딩하면 어드민 밖으로 나가는 경로는 안 받는다.
        Seen escaped = run(enabled(), "/api/admin/%2e%2e/rooms", "Bearer " + SERVICE_TOKEN);
        assertThat(escaped.auth()).isNull();
    }

    @Test
    void context_path_is_excluded_from_the_prefix_check() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/zz/api/admin/chat-reports");
        req.setContextPath("/zz");
        Seen seen = run(enabled(), req, "Bearer " + SERVICE_TOKEN);
        assertThat(seen.principal()).isEqualTo(new AuthPrincipal(SERVICE_USER_ID, "hub-service"));
    }

    @Test
    void disabled_when_token_blank_or_user_id_not_positive() throws Exception {
        for (AdminServiceProperties props : new AdminServiceProperties[] {
                new AdminServiceProperties("", SERVICE_USER_ID),
                new AdminServiceProperties(null, SERVICE_USER_ID),
                new AdminServiceProperties("   ", SERVICE_USER_ID),
                new AdminServiceProperties(SERVICE_TOKEN, 0L),
                new AdminServiceProperties(SERVICE_TOKEN, -1L)}) {
            Seen seen = run(props, "/api/admin/chat-reports", "Bearer " + SERVICE_TOKEN);
            assertThat(seen.auth()).as(props.toString()).isNull();
        }
        // 빈 토큰이면 빈 Bearer 값으로도 열리지 않는다.
        assertThat(run(new AdminServiceProperties("", SERVICE_USER_ID),
                "/api/admin/chat-reports", "Bearer ").auth()).isNull();
    }

    @Test
    void no_authorization_header_is_unauthenticated() throws Exception {
        assertThat(run(enabled(), "/api/admin/chat-reports", null).auth()).isNull();
    }

    @Test
    void regular_jwt_still_authenticates_on_any_path() throws Exception {
        String jwt = jwtService.issue(5L, "alice01").token();

        Seen admin = run(enabled(), "/api/admin/chat-reports", "Bearer " + jwt);
        assertThat(admin.principal()).isEqualTo(new AuthPrincipal(5L, "alice01"));
        assertThat(admin.mdcUserId()).isEqualTo("5");

        Seen rooms = run(enabled(), "/api/rooms", "Bearer " + jwt);
        assertThat(rooms.principal()).isEqualTo(new AuthPrincipal(5L, "alice01"));

        // 서비스 토큰이 꺼져 있어도 JWT 는 그대로.
        Seen off = run(new AdminServiceProperties("", 0L), "/api/rooms", "Bearer " + jwt);
        assertThat(off.principal()).isEqualTo(new AuthPrincipal(5L, "alice01"));
    }
}
