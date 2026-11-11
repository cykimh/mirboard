package com.mirboard.infra.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-90 — 레이트리밋 버킷 카탈로그. `mirboard.ratelimit.buckets.{이름}.{limit,window}` 로
 * 바인딩되며, 설정에 없는 버킷은 아래 기본값을 쓴다.
 *
 * <p>기본값은 "정상 사용자는 절대 못 느끼고 스크립트 스팸만 걸린다"를 기준으로 잡았다.
 * 특히 {@link #GAME_ACTION} 은 정상 연타(봄버·연속 패스)와 봇 매치가 걸리면 게임이
 * 멈추므로 넉넉하다 — 한도를 조이는 것은 실제 트래픽 분포를 본 뒤에 한다.
 *
 * <p>{@code enabled=false} 면 전 버킷을 무제한으로 만든다(사고 시 즉시 끄기용).
 *
 * <p>{@code clientIpHeader} — D-117. IP 키에 쓸 클라 IP 를 읽을 <b>신뢰 헤더</b> 이름
 * (운영 {@code Fly-Client-IP}). 비우면 {@code remoteAddr}. 해석은 {@link ClientIpResolver}.
 */
@ConfigurationProperties("mirboard.ratelimit")
public class RateLimitProperties {

    // ── 버킷 이름 상수 (어댑터의 라우팅 표가 참조) ──
    public static final String AUTH = "auth";
    /** D-117 — 게스트 생성. IP(IPv6 /64) 당 하루 한도. */
    public static final String GUEST = "guest";
    public static final String EXPENSIVE_WRITE = "expensive-write";
    public static final String ROOM_CREATE = "room-create";
    public static final String API_DEFAULT = "api-default";
    public static final String GAME_ACTION = "game-action";
    /** D-229 — 상태를 바꾸지 않는 미리보기 프레임(차례 주인 초당 4번 + 2초 유지). */
    public static final String GAME_PREVIEW = "game-preview";
    public static final String CHAT = "chat";
    public static final String REACTION = "reaction";
    /** D-155 — 음성 신호. 연결을 맺을 때 ICE 후보가 한꺼번에 몰린다(상대당 수십 개). */
    public static final String VOICE = "voice";
    public static final String STOMP_DEFAULT = "stomp-default";

    private static final Map<String, RateLimitPolicy> DEFAULTS = Map.ofEntries(
            // D-84 값 보존 — 인증은 IP 당 분당 20.
            Map.entry(AUTH, new RateLimitPolicy(AUTH, 20, Duration.ofMinutes(1))),
            // D-117 — 게스트는 방문자마다 users 행을 만든다. 정상 리뷰어는 하루 한두 번이면 충분.
            Map.entry(GUEST, new RateLimitPolicy(GUEST, 10, Duration.ofHours(24))),
            // 아바타 업로드(128px PNG → BYTEA)·비밀번호 변경(BCrypt).
            Map.entry(EXPENSIVE_WRITE, new RateLimitPolicy(EXPENSIVE_WRITE, 10, Duration.ofMinutes(1))),
            // 방 생성은 Lua 트랜잭션 + 봇 시드.
            Map.entry(ROOM_CREATE, new RateLimitPolicy(ROOM_CREATE, 10, Duration.ofMinutes(1))),
            // 그 외 /api/** 전부 — 관전/랭킹/resync 폴링을 넉넉히 흡수.
            Map.entry(API_DEFAULT, new RateLimitPolicy(API_DEFAULT, 300, Duration.ofMinutes(1))),
            // 인게임 액션 — 정상 플레이가 절대 안 걸리는 수준. D-228 — 걷기 확정(MOVE, 초당 4번까지)이 생겨 10초 30 → 60.
            Map.entry(GAME_ACTION, new RateLimitPolicy(GAME_ACTION, 60, Duration.ofSeconds(10))),
            // D-229 — 미리보기. 초당 4번 + 유지 = 4.5번의 여유.
            Map.entry(GAME_PREVIEW, new RateLimitPolicy(GAME_PREVIEW, 60, Duration.ofSeconds(10))),
            Map.entry(CHAT, new RateLimitPolicy(CHAT, 10, Duration.ofSeconds(10))),
            Map.entry(REACTION, new RateLimitPolicy(REACTION, 20, Duration.ofSeconds(10))),
            Map.entry(VOICE, new RateLimitPolicy(VOICE, 300, Duration.ofSeconds(10))),
            Map.entry(STOMP_DEFAULT, new RateLimitPolicy(STOMP_DEFAULT, 60, Duration.ofSeconds(10))));

    private boolean enabled = true;
    private Map<String, Bucket> buckets = new LinkedHashMap<>();
    private String clientIpHeader;

    /**
     * 버킷 정책 조회 — 설정 override 가 있으면 그것, 없으면 기본값.
     * 알 수 없는 이름이면 {@link #API_DEFAULT} 로 폴백한다(호출부 오타가 무제한이
     * 되는 것보다 낫다).
     */
    public RateLimitPolicy policy(String name) {
        if (!enabled) {
            return new RateLimitPolicy(name, 0, Duration.ofSeconds(1));
        }
        Bucket override = buckets.get(name);
        if (override != null) {
            return new RateLimitPolicy(name, override.getLimit(), override.getWindow());
        }
        RateLimitPolicy fallback = DEFAULTS.get(name);
        return fallback != null ? fallback : DEFAULTS.get(API_DEFAULT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientIpHeader() {
        return clientIpHeader;
    }

    public void setClientIpHeader(String clientIpHeader) {
        this.clientIpHeader = clientIpHeader;
    }

    public Map<String, Bucket> getBuckets() {
        return buckets;
    }

    public void setBuckets(Map<String, Bucket> buckets) {
        this.buckets = buckets == null ? new LinkedHashMap<>() : buckets;
    }

    /** 설정 바인딩용 가변 홀더 (record 는 Map 바인딩에서 setter 를 못 써서 클래스). */
    public static class Bucket {
        private int limit;
        private Duration window = Duration.ofMinutes(1);

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window == null ? Duration.ofMinutes(1) : window;
        }
    }
}
