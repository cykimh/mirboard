package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-concurrency-test-secret-must-be-32-bytes-or-more"
})
class RoomServiceConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> MYSQL = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    RoomService roomService;

    @Autowired
    RoomRepository repository;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void nine_concurrent_joins_do_not_exceed_capacity() throws InterruptedException {
        Room created = roomService.createRoom(1L, "concurrency-test", "TICHU");
        String roomId = created.roomId();

        int joiners = 9;
        ExecutorService pool = Executors.newFixedThreadPool(joiners);
        CountDownLatch ready = new CountDownLatch(joiners);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        AtomicInteger gameStarted = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (long uid = 2; uid <= 1 + joiners; uid++) {
            final long user = uid;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    roomService.joinRoom(roomId, user);
                    success.incrementAndGet();
                } catch (RoomFullException e) {
                    full.incrementAndGet();
                } catch (GameAlreadyStartedException e) {
                    // After the 4th join the room transitions to IN_GAME; later
                    // attempts may race past the FULL check and see this state.
                    gameStarted.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        Room finalRoom = roomService.getRoom(roomId);

        assertThat(finalRoom.playerCount())
                .as("Player count must never exceed capacity")
                .isEqualTo(4);
        assertThat(success.get())
                .as("Exactly capacity - 1 (host) successful joins")
                .isEqualTo(3);
        assertThat(full.get() + gameStarted.get())
                .as("All other joiners rejected with FULL or GAME_ALREADY_STARTED")
                .isEqualTo(6);
        assertThat(other.get()).as("No unexpected exceptions").isZero();
        assertThat(finalRoom.status())
                .as("Phase 16(#2): join 만으로는 시작 안 함 — 정원 차도 WAITING")
                .isEqualTo(RoomStatus.WAITING);
    }

    @Test
    void leave_decrements_count_and_promotes_host() {
        Room created = roomService.createRoom(100L, "leave-test", "TICHU");
        String roomId = created.roomId();
        roomService.joinRoom(roomId, 101L);
        roomService.joinRoom(roomId, 102L);

        // host (100) leaves → 101 becomes host
        roomService.leaveRoom(roomId, 100L);

        Room after = roomService.getRoom(roomId);
        assertThat(after.playerCount()).isEqualTo(2);
        assertThat(after.hostId()).isEqualTo(101L);
    }

    @Test
    void last_member_leaving_destroys_the_room() {
        Room created = roomService.createRoom(200L, "ephemeral", "TICHU");
        String roomId = created.roomId();

        roomService.leaveRoom(roomId, 200L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roomService.getRoom(roomId))
                .isInstanceOf(RoomNotFoundException.class);
    }

    /**
     * BY-3 — 좌석 빼기와 준비 지우기가 한 원자 구간이어야 한다. 예전엔 room_leave.lua 가 좌석만 빼고 준비는 자바가 따로 지워,
     * 그 사이 다른 사람의 준비가 떠난 사람의 준비까지 세어 시작될 수 있었다.
     */
    @Test
    void leave_script_clears_the_leavers_ready_flag_atomically() {
        String roomId = roomService.createRoom(300L, "by3-leave", "TICHU").roomId();
        roomService.joinRoom(roomId, 301L);
        roomService.setReady(roomId, 301L, true);

        repository.leave(roomId, 301L);

        assertThat(redis.opsForSet().isMember("room:" + roomId + ":ready", "301"))
                .as("leave 스크립트만으로 준비가 지워진다")
                .isFalse();
    }

    /**
     * BY-3 → D-161 — 친구 방(사람만)은 준비가 표시일 뿐이라, 좌석에 없는 준비가 남아 있어도 앉은 전원이 준비해도
     * 시작하지 않는다. 시작은 방장의 startNow 뿐이고, 남은 준비는 거기에 영향을 주지 않는다.
     */
    @Test
    void stale_ready_of_a_non_seated_user_does_not_start_the_game() {
        String roomId = roomService.createRoom(400L, "by3-stale", "TICHU").roomId();
        roomService.joinRoom(roomId, 401L);
        roomService.joinRoom(roomId, 402L);
        roomService.joinRoom(roomId, 403L);
        redis.opsForSet().add("room:" + roomId + ":ready", "499"); // 떠난 사람의 남은 준비(경합 창)
        roomService.setReady(roomId, 400L, true);
        roomService.setReady(roomId, 401L, true);

        roomService.setReady(roomId, 402L, true); // 앉은 4명 중 3명 + 남은 준비 1 = 4

        assertThat(roomService.getRoom(roomId).status())
                .as("403 이 준비하지 않았으니 대기 그대로")
                .isEqualTo(RoomStatus.WAITING);
        roomService.setReady(roomId, 403L, true);
        assertThat(roomService.getRoom(roomId).status())
                .as("D-161 — 앉은 전원이 준비해도 친구 방은 자동 시작하지 않는다")
                .isEqualTo(RoomStatus.WAITING);
        assertThat(roomService.startNow(roomId, 400L).status())
                .as("방장이 시작하면 시작된다")
                .isEqualTo(RoomStatus.IN_GAME);
    }
}
