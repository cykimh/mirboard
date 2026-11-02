package com.mirboard.infra.config;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.auth.InvalidCredentialsException;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.infra.web.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Bearer 토큰 → {@link AuthPrincipal}. 사람은 JWT, 허브 서버는 서비스 토큰(D-170, {@code /api/admin/**} 한정).
 *
 * <p>서비스 토큰 분기를 별도 필터가 아니라 여기에 둔 이유: JWT 해석 실패 때 아래에서
 * {@code clearContext()} 를 부르므로, 앞선 필터가 심은 서비스 인증이 지워진다.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_PREFIX = "/api/admin/";
    static final String SERVICE_USERNAME = "hub-service";

    private final JwtService jwtService;
    private final AdminServiceProperties adminService;
    private final byte[] serviceTokenBytes;

    public JwtAuthFilter(JwtService jwtService, AdminServiceProperties adminService) {
        this.jwtService = jwtService;
        this.adminService = adminService;
        this.serviceTokenBytes = adminService.serviceToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        AuthPrincipal authenticated = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            authenticated = isServiceToken(request, token)
                    ? new AuthPrincipal(adminService.serviceUserId(), SERVICE_USERNAME)
                    : parseJwt(token);
            if (authenticated != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(authenticated, null, List.of()));
            }
        }
        try (var _ = MdcKeys.scope()
                .userId(authenticated != null ? authenticated.userId() : null)) {
            chain.doFilter(request, response);
        }
    }

    /** 서비스 토큰 아닌 Bearer 는 기존대로 JWT. 실패하면 컨텍스트를 비우고 null. */
    private AuthPrincipal parseJwt(String token) {
        try {
            return jwtService.parse(token);
        } catch (InvalidCredentialsException ignored) {
            SecurityContextHolder.clearContext();
            return null;
        }
    }

    /** D-170 — 어드민 경로 + 설정된 서비스 토큰과 상수 시간 비교로 같을 때만. 꺼져 있으면 항상 false. */
    private boolean isServiceToken(HttpServletRequest request, String token) {
        if (!adminService.enabled() || token.isEmpty() || !isAdminPath(request)) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), serviceTokenBytes);
    }

    /**
     * MVC·Security 가 매칭하는 경로(디코딩·{@code ;} 제거·contextPath 제외)에서 {@code ..} 까지 정리한 값이
     * {@code /api/admin/} 로 시작할 때만 true. 해석할 수 없으면 false(닫는 쪽).
     */
    static boolean isAdminPath(HttpServletRequest request) {
        String path;
        try {
            path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return StringUtils.cleanPath(path).startsWith(ADMIN_PREFIX);
    }
}
