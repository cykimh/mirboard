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
 * userId 키를, 미인증은 IP 키를 쓴다.
 *
 * <p>D-117 — 단, <b>인증 경로(`ipOnly` 라우트)는 Bearer 유무와 무관하게 언제나 IP 키</b>다.
 * 예전엔 `/api/auth/**` 에 토큰을 실으면 userId 키로 바뀌어, 계정을 하나 만들 때마다 그
 * 토큰으로 새 버킷을 얻는 식으로 IP 버킷을 우회할 수 있었다. IP 자체는
 * {@link ClientIpResolver} 가 신뢰 헤더에서 읽는다(XFF 위조 무력화).
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

    /**
     * (메서드, 경로 prefix) → 버킷. 위에서부터 첫 매칭. 메서드 null 이면 전 메서드.
     * {@code ipOnly} 면 인증 주체를 무시하고 IP 키(D-117).
     */
    private static final List<Route> ROUTES = List.of(
            // D-117 — 정확 일치. 아래 `/api/auth/` prefix 보다 먼저여야 하루 한도 버킷을 탄다.
            new Route("POST", "/api/auth/guest", RateLimitProperties.GUEST, true),
            new Route("POST", "/api/auth/", RateLimitProperties.AUTH, true),
            new Route(null, "/api/me/avatar", RateLimitProperties.EXPENSIVE_WRITE, false),
            new Route("PUT", "/api/me/password", RateLimitProperties.EXPENSIVE_WRITE, false),
            // D-93 — 신고는 DB write + 링버퍼 스캔이라 고비용, 남용 여지도 크다.
            new Route("POST", "/api/chat/reports", RateLimitProperties.EXPENSIVE_WRITE, false),
            new Route("POST", "/api/rooms", RateLimitProperties.ROOM_CREATE, false));

    /** 표에 없는 `/api/**` — 기본 버킷, 인증되면 userId 키. */
    private static final Route FALLBACK =
            new Route(null, "/api/", RateLimitProperties.API_DEFAULT, false);

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitProperties props;

    public HttpRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper,
                               ClientIpResolver clientIpResolver, RateLimitProperties props) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
        this.props = props;
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
        Route route = routeFor(request);
        String bucket = route.bucket();
        String clientIp = clientIpResolver.resolve(request);
        String subject = route.ipOnly()
                ? RateLimitSubject.ofIp(clientIp)
                : RateLimitSubject.of(currentPrincipal(), clientIp);

        if (rateLimiter.tryAcquire(bucket, subject)) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, bucket);
    }

    /** `POST /api/rooms` 는 방 생성만 잡고 `POST /api/rooms/{id}/...` 는 기본 버킷으로. */
    static String bucketFor(HttpServletRequest request) {
        return routeFor(request).bucket();
    }

    /** 인증 주체를 무시하고 IP 키를 쓰는 경로인지 (D-117). */
    static boolean ipOnlyFor(HttpServletRequest request) {
        return routeFor(request).ipOnly();
    }

    private static Route routeFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        for (Route route : ROUTES) {
            if (route.matches(method, uri)) {
                return route;
            }
        }
        return FALLBACK;
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
        // 클라가 언제 재시도할지 알 수 있게. 고정 윈도라 최대 대기 = 윈도 길이(D-117: 예전엔
        // 고정 60 이라 하루 윈도 버킷에도 "1분 뒤 다시"를 알렸다).
        response.setHeader(HttpHeaders.RETRY_AFTER,
                Long.toString(props.policy(bucket).windowSeconds()));
        objectMapper.writeValue(response.getWriter(), ApiErrorEnvelope.of(
                "TOO_MANY_REQUESTS",
                "요청이 너무 많습니다. 잠시 후 다시 시도하세요."));
    }

    private record Route(String method, String pathPrefix, String bucket, boolean ipOnly) {
        boolean matches(String requestMethod, String uri) {
            if (method != null && !method.equalsIgnoreCase(requestMethod)) {
                return false;
            }
            // "/api/rooms" 는 정확히 그 경로만(하위 액션 제외), 그 외는 prefix 매칭.
            return pathPrefix.endsWith("/") ? uri.startsWith(pathPrefix) : uri.equals(pathPrefix);
        }
    }
}
