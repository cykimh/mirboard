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

    @Test
    void auth_endpoints_use_the_auth_bucket() {
        assertThat(httpBucket("POST", "/api/auth/login")).isEqualTo(RateLimitProperties.AUTH);
        assertThat(httpBucket("POST", "/api/auth/register")).isEqualTo(RateLimitProperties.AUTH);
    }

    @Test
    void expensive_writes_use_their_own_bucket() {
        assertThat(httpBucket("POST", "/api/me/avatar"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
        assertThat(httpBucket("DELETE", "/api/me/avatar"))
                .isEqualTo(RateLimitProperties.EXPENSIVE_WRITE);
        assertThat(httpBucket("PUT", "/api/me/password"))
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
