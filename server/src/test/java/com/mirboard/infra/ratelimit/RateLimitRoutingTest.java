package com.mirboard.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * D-90 — 버킷 라우팅 표와 정책 조회의 순수 로직 검증(Docker 불필요).
 *
 * <p>여기서 지키려는 불변식은 "어떤 경로도 버킷 없이 통과하지 않는다"다 — 표에
 * 없으면 반드시 기본 버킷으로 떨어져야 route-drift 로 무보호 엔드포인트가 생기지 않는다.
 */
class RateLimitRoutingTest {

    private static String httpBucket(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        return HttpRateLimitFilter.bucketFor(req);
    }

    private static boolean ipOnly(String method, String uri) {
        return HttpRateLimitFilter.ipOnlyFor(new MockHttpServletRequest(method, uri));
    }

    @Test
    void auth_endpoints_use_the_auth_bucket() {
        assertThat(httpBucket("POST", "/api/auth/login")).isEqualTo(RateLimitProperties.AUTH);
        assertThat(httpBucket("POST", "/api/auth/register")).isEqualTo(RateLimitProperties.AUTH);
    }

    @Test
    void guest_creation_has_its_own_ip_only_bucket() {
        // D-117 — `/api/auth/` prefix 보다 먼저 매칭돼야 auth(분당 20)가 아니라 하루 한도를 쓴다.
        assertThat(httpBucket("POST", "/api/auth/guest")).isEqualTo(RateLimitProperties.GUEST);
        assertThat(ipOnly("POST", "/api/auth/guest")).isTrue();
        // 정확 일치 — 하위 경로는 일반 auth 버킷.
        assertThat(httpBucket("POST", "/api/auth/guest/x")).isEqualTo(RateLimitProperties.AUTH);
    }

    @Test
    void guest_bucket_defaults_to_ten_per_day() {
        RateLimitPolicy policy = new RateLimitProperties().policy(RateLimitProperties.GUEST);
        assertThat(policy.limit()).isEqualTo(10);
        assertThat(policy.window()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void auth_routes_are_ip_only_and_user_routes_are_not() {
        // D-117 — 인증 경로는 Bearer 를 실어도 IP 키다. userId 키로 바뀌면 토큰을 이어 붙여
        // (가입 → 그 토큰으로 또 가입) IP 버킷을 우회할 수 있었다.
        assertThat(ipOnly("POST", "/api/auth/login")).isTrue();
        assertThat(ipOnly("POST", "/api/auth/register")).isTrue();
        // 인증된 사용자 경로는 그대로 userId 키(D-90 NAT 오탐 회피).
        assertThat(ipOnly("POST", "/api/rooms")).isFalse();
        assertThat(ipOnly("POST", "/api/me/avatar")).isFalse();
        assertThat(ipOnly("GET", "/api/games")).isFalse();
    }

    @Test
    void routing_judges_the_decoded_path_that_mvc_and_security_match() {
        // D-117 보정 — 원본 URI 로 판정하면 `%67uest` 같은 인코딩 변형이 같은 컨트롤러에 닿으면서
        // 버킷만 바뀌었다(게스트 하루 10 → auth 분당 20, auth → api-default + userId 키).
        assertThat(httpBucket("POST", "/api/auth/%67uest")).isEqualTo(RateLimitProperties.GUEST);
        assertThat(ipOnly("POST", "/api/auth/%67uest")).isTrue();
        assertThat(httpBucket("POST", "/%61pi/auth/guest")).isEqualTo(RateLimitProperties.GUEST);
        assertThat(httpBucket("POST", "/api/%61uth/register")).isEqualTo(RateLimitProperties.AUTH);
        assertThat(ipOnly("POST", "/api/%61uth/register")).isTrue();
    }

    @Test
    void forwarded_prefix_is_stripped_like_the_context_path() {
        // framework 전략의 ForwardedHeaderFilter 는 `X-Forwarded-Prefix: /zz` 를 contextPath 로
        // 바꿔 requestURI 를 `/zz/api/...` 로 만든다. MVC·Security 는 그 뒤의 경로로 매칭한다.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/zz/api/auth/guest");
        req.setContextPath("/zz");
        assertThat(HttpRateLimitFilter.appliesTo(req)).isTrue();
        assertThat(HttpRateLimitFilter.bucketFor(req)).isEqualTo(RateLimitProperties.GUEST);
        assertThat(HttpRateLimitFilter.ipOnlyFor(req)).isTrue();
    }

    private static boolean applies(String uri) {
        return HttpRateLimitFilter.appliesTo(new MockHttpServletRequest("GET", uri));
    }

    @Test
    void filter_scope_leans_to_limiting_when_the_path_is_in_doubt() {
        assertThat(applies("/api/games")).isTrue();
        assertThat(applies("/%61pi/auth/guest")).isTrue();
        // 정적 자산·SPA 딥링크·<img> 아바타는 그대로 제외.
        assertThat(applies("/assets/index-abc.js")).isFalse();
        assertThat(applies("/avatars/7")).isFalse();
        assertThat(applies("/games/r1")).isFalse();
        // 해석할 수 없거나 인코딩이 섞인 경로는 제외하지 않는다 — 판단이 틀릴 때 무보호보다
        // 기본 버킷 한 칸을 쓰는 쪽이 싸다.
        assertThat(applies("/x/%zz")).isTrue();
        assertThat(applies("/games/%ED%8B%B0")).isTrue();
    }

    @Test
    void expensive_writes_use_their_own_bucket() {
        assertThat(httpBucket("POST", "/api/me/avatar"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
        assertThat(httpBucket("DELETE", "/api/me/avatar"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
        assertThat(httpBucket("PUT", "/api/me/password"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
        // D-93 — 채팅 신고도 고비용 쓰기(DB write + 링버퍼 스캔 + 남용 여지).
        assertThat(httpBucket("POST", "/api/chat/reports"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
    }

    @Test
    void room_create_is_separated_from_room_actions() {
        assertThat(httpBucket("POST", "/api/rooms")).isEqualTo(RateLimitProperties.ROOM_CREATE);
        // 방 하위 액션은 생성이 아니므로 일반 버킷 — 준비/입장이 생성 한도를 깎으면 안 된다.
        assertThat(httpBucket("POST", "/api/rooms/abc/ready"))
                .isEqualTo(RateLimitProperties.API_DEFAULT);
        assertThat(httpBucket("GET", "/api/rooms")).isEqualTo(RateLimitProperties.API_DEFAULT);
    }

    @Test
    void unknown_api_paths_fall_back_to_the_default_bucket() {
        assertThat(httpBucket("GET", "/api/users/ranking"))
                .isEqualTo(RateLimitProperties.API_DEFAULT);
        assertThat(httpBucket("POST", "/api/some/brand/new/endpoint"))
                .isEqualTo(RateLimitProperties.API_DEFAULT);
    }

    @Test
    void stomp_destinations_map_to_their_buckets() {
        assertThat(StompRateLimitInterceptor.bucketFor("/app/room/r1/action"))
                .isEqualTo(RateLimitProperties.GAME_ACTION);
        assertThat(StompRateLimitInterceptor.bucketFor("/app/room/r1/chat"))
                .isEqualTo(RateLimitProperties.CHAT);
        assertThat(StompRateLimitInterceptor.bucketFor("/app/lobby/chat"))
                .isEqualTo(RateLimitProperties.CHAT);
        assertThat(StompRateLimitInterceptor.bucketFor("/app/room/r1/reaction"))
                .isEqualTo(RateLimitProperties.REACTION);
    }

    @Test
    void unknown_stomp_destinations_fall_back_to_the_default_bucket() {
        assertThat(StompRateLimitInterceptor.bucketFor("/app/room/r1/something-new"))
                .isEqualTo(RateLimitProperties.STOMP_DEFAULT);
        assertThat(StompRateLimitInterceptor.bucketFor("/app/whatever"))
                .isEqualTo(RateLimitProperties.STOMP_DEFAULT);
    }

    @Test
    void game_action_limit_is_generous_enough_for_normal_play() {
        RateLimitPolicy policy = new RateLimitProperties().policy(RateLimitProperties.GAME_ACTION);
        // 한 사람의 정상 플레이는 초당 1회를 넘기 어렵다. 초당 2회 이상 여유를 강제해
        // 나중에 누가 한도를 조일 때 게임이 막히는 회귀를 막는다.
        double perSecond = policy.limit() / (double) policy.windowSeconds();
        assertThat(perSecond).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void defaults_apply_when_no_override_is_configured() {
        RateLimitProperties props = new RateLimitProperties();
        assertThat(props.policy(RateLimitProperties.AUTH).limit()).isEqualTo(20);
        assertThat(props.policy(RateLimitProperties.AUTH).window()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void configured_override_wins_over_the_default() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Bucket override = new RateLimitProperties.Bucket();
        override.setLimit(3);
        override.setWindow(Duration.ofSeconds(30));
        props.setBuckets(Map.of(RateLimitProperties.AUTH, override));

        RateLimitPolicy policy = props.policy(RateLimitProperties.AUTH);
        assertThat(policy.limit()).isEqualTo(3);
        assertThat(policy.windowSeconds()).isEqualTo(30);
    }

    @Test
    void disabling_the_feature_makes_every_bucket_unlimited() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        assertThat(props.policy(RateLimitProperties.GAME_ACTION).unlimited()).isTrue();
        assertThat(props.policy(RateLimitProperties.AUTH).unlimited()).isTrue();
    }

    @Test
    void an_unknown_bucket_name_falls_back_instead_of_becoming_unlimited() {
        // 호출부 오타가 "무제한"으로 조용히 새는 것보다 기본 버킷이 낫다.
        RateLimitPolicy policy = new RateLimitProperties().policy("typo-bucket");
        assertThat(policy.unlimited()).isFalse();
        assertThat(policy.limit()).isEqualTo(300);
    }

    @Test
    void zero_window_is_clamped_so_it_cannot_become_unlimited() {
        RateLimitPolicy policy = new RateLimitPolicy("x", 5, Duration.ZERO);
        assertThat(policy.windowSeconds()).isEqualTo(1);
    }

    @Test
    void subject_prefers_user_id_over_ip_and_never_collapses_to_one_bucket() {
        assertThat(RateLimitSubject.ofUser(42)).isEqualTo("u:42");
        assertThat(RateLimitSubject.ofIp("10.0.0.1")).isEqualTo("ip:10.0.0.1");
        // IP 를 모를 때 빈 키로 뭉쳐 전체가 한 버킷이 되는 것을 막는다.
        assertThat(RateLimitSubject.ofIp(null)).isEqualTo("ip:unknown");
        assertThat(RateLimitSubject.ofIp("  ")).isEqualTo("ip:unknown");
    }
}
