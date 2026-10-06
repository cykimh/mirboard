package com.mirboard.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.RoomChangedEvent;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.GameEngineProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-116 — <b>도메인 이벤트는 발행한 인스턴스에서 한 번만 처리된다.</b>
 *
 * <p>리스너의 부수효과는 전부 공유 저장소(Redis·Postgres)에 쓰이고, 클라이언트로 나가는
 * 프레임은 {@code StompPublisher} 가 이미 모든 인스턴스로 fan-out 한다. 그러니 도메인
 * 이벤트를 다른 인스턴스에서 <b>다시</b> 처리하면 같은 일이 인스턴스 수만큼 반복될 뿐이다 —
 * 매치 기록·ELO·칩 정산이 두 번, 라운드 딜링이 두 번(나중 패가 먼저 패를 덮어씀).
 *
 * <p>{@code TwoInstanceHandoffIT} 와 같은 방식(같은 Redis/Postgres 를 문 독립 컨텍스트 2개)이되
 * {@code mirboard.messaging.gateway=redis} 로 운영(fly.toml)과 같은 게이트웨이를 켠다.
 * 모든 동작은 A 에서만 일으키고, B 는 아무것도 처리하지 않아야 한다.
 */
@Testcontainers
class DomainEventSingleDeliveryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * 다른 인스턴스로 새는 중복 처리가 있다면 나타나기에 충분한 관찰 창. 구 fan-out 은 Redis
     * 왕복 한 번(수 ms) 안에 B 에서 리스너를 돌렸다.
     */
    private static final Duration QUIET = Duration.ofSeconds(2);

    /** 인스턴스 이름 → 그 인스턴스의 리스너가 받은 도메인 이벤트. 테스트 JVM 안이라 공유된다. */
    static final Map<String, List<Object>> RECEIVED = new ConcurrentHashMap<>();

    @Configuration
    static class ProbeConfig {
        @Bean
        DomainEventProbe domainEventProbe(Environment env) {
            return new DomainEventProbe(env.getRequiredProperty("spring.application.name"));
        }
    }

    /** 실제 리스너(라운드 시작·매치 기록·칩 정산·로비 갱신)와 같은 이벤트를 듣는 계수기. */
    static class DomainEventProbe {
        private final String instance;

        DomainEventProbe(String instance) {
            this.instance = instance;
        }

        @EventListener
        public void on(GameStartingEvent event) {
            record(event);
        }

        @EventListener
        public void on(TichuMatchCompleted event) {
            record(event);
        }

        @EventListener
        public void on(RoomChangedEvent event) {
            record(event);
        }

        private void record(Object event) {
            RECEIVED.computeIfAbsent(instance, k -> new CopyOnWriteArrayList<>()).add(event);
        }
    }

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    private static ConfigurableApplicationContext boot(String name) {
        return new SpringApplicationBuilder(com.mirboard.MirboardApplication.class, ProbeConfig.class)
                .web(WebApplicationType.NONE)
                // D-113 — 커맨드라인 인자로 넘긴다(`.properties(...)` 는 application.yml 에 덮인다).
                .run(
                        "--spring.application.name=" + name,
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                        "--mirboard.jwt.secret=domain-event-test-secret-must-be-32-bytes",
                        // 운영(fly.toml)과 같은 게이트웨이 — 인스턴스 간 Redis Pub/Sub.
                        "--mirboard.messaging.gateway=redis");
    }

    @BeforeAll
    static void bootBoth() {
        instanceA = boot("mirboard-A");
        instanceB = boot("mirboard-B");
    }

    @AfterAll
    static void tearDown() {
        if (instanceA != null) instanceA.close();
        if (instanceB != null) instanceB.close();
    }

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    @Test
    void game_start_deals_once_and_other_instance_handles_nothing() {
        List<Long> ids = registerFour();
        String roomId = startTichuOnA(ids, 0);

        // A 의 로컬 리스너(TichuRoundStarter)는 setReady 안에서 동기로 돌았다 — 패가 이미 있다.
        TichuGameStateStore stateStore = instanceA.getBean(TichuGameStateStore.class);
        List<Card> dealt = stateStore.loadHand(roomId, ids.get(0)).orElseThrow();
        assertThat(received("mirboard-A")).anyMatch(GameStartingEvent.class::isInstance);

        await().during(QUIET).atMost(QUIET.plusSeconds(3)).untilAsserted(() -> {
            // B 가 GameStartingEvent 를 다시 처리하면 새 덱으로 재딜링해 이 패를 덮어쓴다 —
            // 클라는 HAND_DEALT 를 두 번 받고, 순서가 뒤집히면 서버와 다른 패를 쥔다.
            assertThat(stateStore.loadHand(roomId, ids.get(0))).contains(dealt);
            // 방 생성·입장·준비(RoomChangedEvent)까지 포함해 B 는 아무 도메인 이벤트도 처리하지 않는다.
            assertThat(received("mirboard-B")).isEmpty();
        });
    }

    @Test
    void match_completed_is_recorded_and_settled_once() {
        List<Long> ids = registerFour();
        int stake = 100;
        String roomId = startTichuOnA(ids, stake);

        // 좌석 1 탈주 → 상대팀(A: 좌석 0·2) 승리로 매치 종료. 실제 탈주 경로와 같은 포트 호출.
        RoomService roomsA = instanceA.getBean(RoomService.class);
        GameEngine engine = instanceA.getBean(GameEngineProvider.class)
                .forRoom(roomsA.getRoom(roomId));
        List<GameEvent> outbound = new ArrayList<>();
        assertThat(engine.desert(1, ids.get(1), outbound))
                .isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);

        JdbcTemplate jdbc = instanceA.getBean(JdbcTemplate.class);
        RoomChipStore chips = instanceA.getBean(RoomChipStore.class);

        await().during(QUIET).atMost(QUIET.plusSeconds(3)).untilAsserted(() -> {
            assertThat(received("mirboard-B")).noneMatch(TichuMatchCompleted.class::isInstance);

            // 매치 기록 1행 · 참가자 4행.
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tichu_match_results WHERE room_id = ?",
                    Integer.class, roomId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tichu_match_participants p"
                            + " JOIN tichu_match_results r ON r.id = p.match_id"
                            + " WHERE r.room_id = ?",
                    Integer.class, roomId)).isEqualTo(4);

            // 전적·ELO 는 한 번만(D-115 게임별 행) — 신규 유저 K=40, 기대승률 0.5 → ±20.
            // 열: win, lose, rating, desert.
            assertThat(ids.stream().map(id -> tichuStats(jdbc, id)).toList()).containsExactly(
                    List.of(1, 0, 1020, 0),
                    List.of(0, 1, 980, 1),
                    List.of(1, 0, 1020, 0),
                    List.of(0, 1, 980, 0));

            // 칩 정산도 한 번만 — 패자 2명이 100씩 → 승자 2명이 100씩.
            assertThat(chips.stacks(roomId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    ids.get(0), 1100L, ids.get(1), 900L, ids.get(2), 1100L, ids.get(3), 900L));
        });
    }

    // ---------- helpers ----------

    /** A 에서 방 생성 → 4명 입장 → 전원 준비. 마지막 setReady 가 A 에서 게임을 시작한다. */
    private static String startTichuOnA(List<Long> ids, int stake) {
        RoomService roomsA = instanceA.getBean(RoomService.class);
        String roomId = roomsA.createRoom(ids.get(0), "single-delivery", TichuGameDefinition.ID,
                TeamPolicy.SEQUENTIAL, false, RoomService.DEFAULT_TARGET_SCORE,
                RoomService.DEFAULT_TURN_SECONDS, stake).roomId();
        for (int i = 1; i < ids.size(); i++) {
            roomsA.joinRoom(roomId, ids.get(i));
        }
        for (long id : ids) {
            roomsA.setReady(roomId, id, true);
        }
        return roomId;
    }

    private static List<Long> registerFour() {
        UserRepository users = instanceA.getBean(UserRepository.class);
        PasswordEncoder encoder = instanceA.getBean(PasswordEncoder.class);
        Clock clock = instanceA.getBean(Clock.class);
        String prefix = "de" + UUID.randomUUID().toString().substring(0, 6) + "_";
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(users.save(User.create(prefix + i, encoder.encode("validpass1"), clock)).getId());
        }
        return ids;
    }

    private static List<Integer> tichuStats(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject(
                "SELECT win_count, lose_count, rating, desert_count FROM user_game_stats"
                        + " WHERE user_id = ? AND game_type = ?",
                (rs, i) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                userId, TichuGameDefinition.ID);
    }

    private static List<Object> received(String instance) {
        return RECEIVED.getOrDefault(instance, List.of());
    }
}
