package com.mirboard.domain.lobby.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-117 — 게스트 정리의 대상/비대상 경계.
 *
 * <p>지우는 것은 "보존 기간(48h)이 지났고 남긴 흔적이 없는 게스트"뿐이다. 흔적 = 매치를 끝냄
 * (user_game_stats), 신고에 연루(chat_reports 양쪽), 어드민 역할. 시간 경계는 1h 전 vs 96h 전으로
 * 넉넉히 벌려, JVM·DB 세션 시간대 차이(최대 ±14h)가 판정을 뒤집지 못하게 한다.
 *
 * <p>정리는 자기 트랜잭션(REQUIRES_NEW)으로 커밋하므로 테스트 롤백이 없다 — 테스트마다
 * 게스트 행과 그 참조를 직접 지워 격리한다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=guest-sweeper-test-secret-must-be-32-bytes-or-more"
})
class GuestAccountSweeperIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired GuestAccountSweeper sweeper;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired PlatformTransactionManager txManager;
    @Autowired GuestProperties guestProperties;
    @Autowired Clock clock;

    private final SplittableRandom rng = new SplittableRandom();

    @BeforeEach
    void isolate() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
        String guests = "SELECT id FROM users WHERE username LIKE 'guest-%'";
        jdbc.update("DELETE FROM tichu_match_participants WHERE user_id IN (" + guests + ")");
        jdbc.update("DELETE FROM chat_reports WHERE reported_user_id IN (" + guests + ")"
                + " OR reporter_user_id IN (" + guests + ")");
        jdbc.update("DELETE FROM admin_roles WHERE user_id IN (" + guests + ")");
        jdbc.update("DELETE FROM users WHERE username LIKE 'guest-%'");
    }

    @Test
    void deletes_only_expired_guests_without_traces() {
        long expired = guest(96);
        long fresh = guest(1);
        long played = guest(96);
        jdbc.update("INSERT INTO user_game_stats (user_id, game_type) VALUES (?, 'TICHU')", played);
        long member = member(96);
        long reporter = guest(96);
        long reported = guest(96);
        report(reported, member);
        report(member, reporter);
        long admin = guest(96);
        jdbc.update("INSERT INTO admin_roles (user_id) VALUES (?)", admin);
        // 접두·해시가 같아도 봇이면 손대지 않는다(is_bot 재확인).
        long botLike = insertRaw(GuestPolicy.newUsername(rng), GuestPolicy.NO_LOGIN_HASH, true, 96);
        // 접두가 같아도 해시가 sentinel 이 아니면 게스트가 아니다.
        long realHash = insertRaw(GuestPolicy.newUsername(rng), "$2a$10$abcdefghijklmnopqrstuv", false, 96);

        var result = sweeper.sweepOnce();

        assertThat(users.existsById(expired)).isFalse();
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(users.existsById(fresh)).isTrue();
        assertThat(users.existsById(played)).isTrue();
        assertThat(users.existsById(member)).isTrue();
        assertThat(users.existsById(reporter)).isTrue();
        assertThat(users.existsById(reported)).isTrue();
        assertThat(users.existsById(admin)).isTrue();
        assertThat(users.existsById(botLike)).isTrue();
        assertThat(users.existsById(realHash)).isTrue();
    }

    @Test
    void poison_row_is_skipped_and_the_rest_of_the_batch_is_deleted() {
        long before = guest(96);
        long poison = guest(96);
        participatedWithoutStats(poison);
        long after = guest(96);

        var result = sweeper.sweepOnce();

        assertThat(users.existsById(poison)).isTrue();
        assertThat(users.existsById(before)).isFalse();
        assertThat(users.existsById(after)).isFalse();
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void cursor_moves_past_a_full_batch_of_poison_rows() {
        // 배치 2 에 독 행 2개가 앞(id 작은 쪽)에 있으면 첫 회는 둘 다 건너뛴다. 커서가 없으면
        // 매번 같은 두 행만 보고 뒤의 대상은 영영 안 지워진다(정체).
        var small = new GuestAccountSweeper(users, redis,
                new GuestProperties(true, guestProperties.dailyCap(), guestProperties.retention(), 2),
                txManager, clock);
        long poison1 = guest(96);
        participatedWithoutStats(poison1);
        long poison2 = guest(96);
        participatedWithoutStats(poison2);
        long target = guest(96);

        var first = small.sweepOnce();
        assertThat(first.deleted()).isZero();
        assertThat(users.existsById(target)).isTrue();

        var second = small.sweepOnce();
        assertThat(second.deleted()).isEqualTo(1);
        assertThat(users.existsById(target)).isFalse();

        // 끝까지 훑었으면 처음으로 돌아간다 — 다음 회차는 다시 앞에서부터.
        assertThat(redis.opsForValue().get(GuestAccountSweeper.CURSOR_KEY)).isEqualTo("0");
    }

    @Test
    void sweep_if_due_runs_at_most_once_per_lock_window() {
        long first = guest(96);
        sweeper.sweepIfDue();
        assertThat(users.existsById(first)).isFalse();

        long second = guest(96);
        sweeper.sweepIfDue();   // 락(10분) 안 — 무시.
        assertThat(users.existsById(second)).isTrue();
        assertThat(redis.getExpire(GuestAccountSweeper.LOCK_KEY)).isPositive();
    }

    private long guest(int hoursAgo) {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofHours(hoursAgo)), ZoneOffset.UTC);
        return users.save(User.create(GuestPolicy.newUsername(rng), GuestPolicy.NO_LOGIN_HASH, past)).getId();
    }

    private long member(int hoursAgo) {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofHours(hoursAgo)), ZoneOffset.UTC);
        String name = "m" + Long.toHexString(rng.nextLong()).substring(0, 10);
        return users.save(User.create(name, "$2a$10$abcdefghijklmnopqrstuv", past)).getId();
    }

    /** 엔티티에 is_bot 세터가 없어 봇 형태 행은 SQL 로 심는다(created_at 은 UTC 벽시계로 저장). */
    private long insertRaw(String username, String hash, boolean bot, int hoursAgo) {
        var createdAt = Timestamp.valueOf(java.time.LocalDateTime.ofInstant(
                clock.instant().minus(Duration.ofHours(hoursAgo)), ZoneOffset.UTC));
        return jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, is_bot, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, username, hash, bot, createdAt);
    }

    private void report(long reportedUserId, long reporterUserId) {
        jdbc.update("INSERT INTO chat_reports (event_id, scope, reported_user_id, reporter_user_id,"
                        + " message, message_at) VALUES (?, 'LOBBY', ?, ?, 'msg', CURRENT_TIMESTAMP)",
                java.util.UUID.randomUUID().toString(), reportedUserId, reporterUserId);
    }

    /** D-115 계약을 어긴 행 — 참가 기록은 있는데 user_game_stats 가 없다(FK 로 못 지움). */
    private void participatedWithoutStats(long userId) {
        Long matchId = jdbc.queryForObject(
                "INSERT INTO tichu_match_results (room_id, team_a_score, team_b_score, payload_json)"
                        + " VALUES ('poison-room', 0, 0, '{}') RETURNING id", Long.class);
        jdbc.update("INSERT INTO tichu_match_participants (match_id, user_id, team, is_win)"
                + " VALUES (?, ?, 'A', false)", matchId, userId);
    }
}
