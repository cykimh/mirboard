package com.mirboard.infra.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.WsSessionLifecycleListener;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * D-96 — 수평 확장 인프라 2종(프레즌스 · 데드라인 큐) 검증.
 *
 * <p>여기서 지키려는 불변식은 단일 인스턴스 테스트로는 안 보이는 것들이다:
 * <ul>
 *   <li>프레즌스가 <b>세션 카운터</b>라 탭 하나를 닫아도 접속 유지로 보인다
 *       (boolean 이었다면 재접속을 탈주로 오판한다).</li>
 *   <li>여러 폴러가 동시에 pop 해도 <b>한 항목은 정확히 하나</b>에게만 간다
 *       (아니면 중복 자동행동이 난다).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=m3-infra-test-secret-must-be-32-bytes-min"
})
class DistributedInfraIT {

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

    @Autowired RoomPresence presence;
    @Autowired WsSessionLifecycleListener lifecycle;
    @Autowired DeadlineQueue deadlines;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flush() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    // ---------- 프레즌스 ----------

    @Test
    void presence_tracks_sessions_across_what_would_be_separate_instances() {
        String room = UUID.randomUUID().toString();
        // 서로 다른 인스턴스가 등록했다고 가정한 두 세션 — 저장소가 공유라 둘 다 보인다.
        presence.join("sess-A", 11L, room);
        presence.join("sess-B", 22L, room);

        assertThat(presence.hasLiveSession(11L, room)).isTrue();
        assertThat(presence.hasLiveSession(22L, room)).isTrue();
        assertThat(presence.viewers(room)).containsExactlyInAnyOrder(11L, 22L);
    }

    @Test
    void closing_one_of_two_tabs_keeps_the_user_present() {
        String room = UUID.randomUUID().toString();
        presence.join("tab-1", 11L, room);
        presence.join("tab-2", 11L, room);

        presence.leave("tab-1");

        // 여기서 false 가 나오면 탭 하나 닫은 사용자를 탈주로 처리하게 된다.
        assertThat(presence.hasLiveSession(11L, room)).isTrue();

        presence.leave("tab-2");
        assertThat(presence.hasLiveSession(11L, room)).isFalse();
        assertThat(presence.viewers(room)).isEmpty();
    }

    @Test
    void leave_returns_the_session_owner_so_disconnect_can_act_without_local_state() {
        String room = UUID.randomUUID().toString();
        presence.join("sess-X", 42L, room);

        var info = presence.leave("sess-X");

        assertThat(info).isPresent();
        assertThat(info.get().userId()).isEqualTo(42L);
        assertThat(info.get().roomId()).isEqualTo(room);
        // 모르는 세션은 조용히 empty — DISCONNECT 가 중복으로 와도 안전해야 한다.
        assertThat(presence.leave("sess-X")).isEmpty();
        assertThat(presence.leave("never-registered")).isEmpty();
    }

    @Test
    void one_session_counts_once_however_many_room_topics_it_subscribes() {
        String room = UUID.randomUUID().toString();
        // 실제 클라(useStompRoom)는 한 세션에서 방 토픽을 3개 구독한다 —
        // `/topic/room/{id}`, `/chat`, `/reaction`. 리스너는 구독마다 join 을 부르는데
        // DISCONNECT 는 세션당 한 번뿐이므로, join 이 멱등하지 않으면 잔여 카운터가 남아
        // `hasLiveSession` 이 영영 true 가 된다(= 탈주가 확정되지 않는다).
        presence.join("sess-1", 11L, room);
        presence.join("sess-1", 11L, room);
        presence.join("sess-1", 11L, room);

        presence.leave("sess-1");

        assertThat(presence.hasLiveSession(11L, room)).isFalse();
        assertThat(presence.viewers(room)).isEmpty();
    }

    @Test
    void reusing_a_session_for_another_room_does_not_leak_the_previous_room() {
        String roomA = UUID.randomUUID().toString();
        String roomB = UUID.randomUUID().toString();
        presence.join("sess-2", 11L, roomA);
        presence.join("sess-2", 11L, roomB);

        // 세션이 실제로 보고 있는 방은 B 다. A 가 남으면 그 방에서 영원히 접속 중으로
        // 보이며, 탈주 회피에 악용할 수 있다(클라가 보낸 구독은 검증 대상).
        assertThat(presence.hasLiveSession(11L, roomA)).isFalse();
        assertThat(presence.hasLiveSession(11L, roomB)).isTrue();

        presence.leave("sess-2");
        assertThat(presence.hasLiveSession(11L, roomB)).isFalse();
    }

    @Test
    void subscribing_the_three_room_topics_then_disconnecting_clears_presence() {
        String room = UUID.randomUUID().toString();
        AuthPrincipal user = new AuthPrincipal(11L, "tester");

        // 프로덕션 경로 그대로: 한 세션이 방 토픽 3개를 구독한 뒤 끊긴다.
        lifecycle.onSubscribe(subscribeEvent("ws-1", "/topic/room/" + room, user));
        lifecycle.onSubscribe(subscribeEvent("ws-1", "/topic/room/" + room + "/chat", user));
        lifecycle.onSubscribe(subscribeEvent("ws-1", "/topic/room/" + room + "/reaction", user));
        assertThat(presence.hasLiveSession(11L, room)).isTrue();

        lifecycle.onDisconnect(disconnectEvent("ws-1"));

        assertThat(presence.hasLiveSession(11L, room)).isFalse();
    }

    private static SessionSubscribeEvent subscribeEvent(String sessionId,
                                                        String destination,
                                                        AuthPrincipal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(new Object(), message, user);
    }

    private static SessionDisconnectEvent disconnectEvent(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }

    // ---------- 데드라인 큐 ----------

    @Test
    void only_expired_deadlines_are_returned() {
        deadlines.schedule("turn", "room-1", Duration.ZERO);
        deadlines.schedule("turn", "room-2", Duration.ofHours(1));

        List<String> due = deadlines.pollDue("turn");

        assertThat(due).containsExactly("room-1");
        // pop 이므로 두 번째 폴링에는 안 나온다 — 재실행 방지의 근거.
        assertThat(deadlines.pollDue("turn")).isEmpty();
    }

    @Test
    void rescheduling_the_same_member_replaces_the_previous_deadline() {
        deadlines.schedule("turn", "room-1", Duration.ZERO);
        // 턴이 진행돼 타이머를 미룸 — 기존 항목이 남아 있으면 즉시 오발화한다.
        deadlines.schedule("turn", "room-1", Duration.ofHours(1));

        assertThat(deadlines.pollDue("turn")).isEmpty();
    }

    /**
     * S5 — 진행 킥은 이 세대의 엔진 타이머를 <b>없을 때만</b> 건다(ZADD NX). 이미 걸린 무장을 덮으면 킥이 락 없이 읽은 낡은 남은
     * 시간으로 정상 무장을 늦출 수 있었다. pop 된 항목은 없는 것이라 다시 걸린다.
     */
    @Test
    void schedule_if_absent_never_overwrites_an_armed_deadline() {
        deadlines.schedule("game", "room-1#3", Duration.ofHours(1));

        assertThat(deadlines.scheduleIfAbsent("game", "room-1#3", Duration.ZERO)).isFalse();
        assertThat(deadlines.pollDue("game")).as("1시간 무장이 그대로 — 0 으로 덮이지 않았다").isEmpty();

        deadlines.schedule("game", "room-2#1", Duration.ZERO);
        assertThat(deadlines.pollDue("game")).containsExactly("room-2#1");
        assertThat(deadlines.scheduleIfAbsent("game", "room-2#1", Duration.ZERO)).as("pop 뒤에는 없다").isTrue();
        assertThat(deadlines.pollDue("game")).containsExactly("room-2#1");
    }

    @Test
    void cancel_removes_a_pending_deadline() {
        deadlines.schedule("desertion", "room-1:7", Duration.ZERO);
        deadlines.cancel("desertion", "room-1:7");

        assertThat(deadlines.pollDue("desertion")).isEmpty();
    }

    @Test
    void concurrent_pollers_never_receive_the_same_deadline_twice() throws Exception {
        // 8개 폴러가 동시에 덤벼도 200개 항목이 정확히 한 번씩만 배분돼야 한다.
        // 여기서 중복이 나면 2인스턴스에서 중복 자동행동(턴 두 번 넘김)이 난다.
        int items = 200;
        for (int i = 0; i < items; i++) {
            deadlines.schedule("turn", "room-" + i, Duration.ZERO);
        }

        int pollers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pollers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> collected = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < pollers; i++) {
            pool.submit(() -> {
                start.await();
                for (int r = 0; r < 20; r++) {
                    collected.addAll(deadlines.pollDue("turn"));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(collected).hasSize(items);
        assertThat(collected.stream().distinct().count()).isEqualTo(items);
    }
}
