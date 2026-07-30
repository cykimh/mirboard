package com.mirboard.infra.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.infra.web.ApiErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * D-90 — `/api/**` 전역 레이트리밋. `JwtAuthFilter` <b>뒤</b>에 등록해 인증된 요청은
 * userId 키를, 미인증(로그인/가입)은 IP 키를 쓴다.
 *
 * <p>버킷은 아래 {@link #ROUTES} 표에서 첫 매칭으로 정하고, 어디에도 안 걸리면
 * {@link RateLimitProperties#API_DEFAULT} 로 떨어진다 — 새 엔드포인트가 조용히
 * 무보호로 태어나는 route-drift 를 막는 fallback(D-61 과 같은 취지).
 *
 * <p>필터는 DispatcherServlet 앞이라 `@RestControllerAdvice` 가 못 잡는다. 그래서
 * 429 응답 본문을 여기서 직접 {@link ApiErrorEnvelope} 형식으로 쓴다 — 컨트롤러
 * 에러와 클라 파싱 경로를 동일하게 유지하기 위함.
 */
@Component
public class HttpRateLimitFilter extends OncePerRequestFilter {

    /** (메서드, 경로 prefix) → 버킷. 위에서부터 첫 매칭. 메서드 null 이면 전 메서드. */
    private static final List<Route> ROUTES = List.of(
            new Route("POST", "/api/auth/", RateLimitProperties.AUTH),
            new Route(null, "/api/me/avatar", RateLimitProperties.EXPENSIVE_WRITE),
            new Route("PUT", "/api/me/password", RateLimitProperties.EXPENSIVE_WRITE),
            new Route("POST", "/api/rooms", RateLimitProperties.ROOM_CREATE));

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public HttpRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /** `/api/**` 만 대상 — 정적 자산·SPA 딥링크·`/avatars/{id}`(<img> 직접 요청)는 제외. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bucket = bucketFor(request);
        String subject = RateLimitSubject.of(currentPrincipal(), request.getRemoteAddr());

        if (rateLimiter.tryAcquire(bucket, subject)) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, bucket);
    }

    /** `POST /api/rooms` 는 방 생성만 잡고 `POST /api/rooms/{id}/...` 는 기본 버킷으로. */
    static String bucketFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        for (Route route : ROUTES) {
            if (route.matches(method, uri)) {
                return route.bucket();
            }
        }
        return RateLimitProperties.API_DEFAULT;
    }

    private static Principal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getPrincipal() instanceof Principal p ? p : null;
    }

    private void writeTooManyRequests(HttpServletResponse response, String bucket)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 클라가 언제 재시도할지 알 수 있게. 고정 윈도라 최대 대기 = 윈도 길이.
        response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        objectMapper.writeValue(response.getWriter(), ApiErrorEnvelope.of(
                "TOO_MANY_REQUESTS",
                "요청이 너무 많습니다. 잠시 후 다시 시도하세요."));
    }

    private record Route(String method, String pathPrefix, String bucket) {
        boolean matches(String requestMethod, String uri) {
            if (method != null && !method.equalsIgnoreCase(requestMethod)) {
                return false;
            }
            // "/api/rooms" 는 정확히 그 경로만(하위 액션 제외), 그 외는 prefix 매칭.
            return pathPrefix.endsWith("/") ? uri.startsWith(pathPrefix) : uri.equals(pathPrefix);
        }
    }
}
