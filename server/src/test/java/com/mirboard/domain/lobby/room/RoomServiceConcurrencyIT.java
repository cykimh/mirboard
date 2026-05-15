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
                .as("Room auto-transitions to IN_GAME once full")
                .isEqualTo(RoomStatus.IN_GAME);
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
}
