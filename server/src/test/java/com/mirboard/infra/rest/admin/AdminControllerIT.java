package com.mirboard.infra.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.lobby.auth.JwtService;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** D-86 — 어드민 매치 강제 종료 + 권한 게이트. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=admin-controller-test-secret-must-be-32-bytes-or-more"
})
class AdminControllerIT {

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

    @Autowired MockMvc mockMvc;
    @Autowired RoomService roomService;
    @Autowired UserRepository userRepo;
    @Autowired AdminRoleRepository adminRoleRepo;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        return userRepo.save(User.create(prefix + SEQ.incrementAndGet(),
                passwordEncoder.encode("validpass1"), clock));
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), u.getUsername()).token();
    }

    /** 봇 없음 + 타임아웃 0 → 4인 ready 후 안정 IN_GAME(자동 진행 없음). */
    private String inGameRoom(User host) {
        User p2 = newUser("ac_p");
        User p3 = newUser("ac_p");
        User p4 = newUser("ac_p");
        Room room = roomService.createRoom(host.getId(), "admin-abort", "TICHU",
                TeamPolicy.SEQUENTIAL, /*fillWithBots*/ false, /*targetScore*/ 300, /*turnSeconds*/ 0);
        String roomId = room.roomId();
        roomService.joinRoom(roomId, p2.getId());
        roomService.joinRoom(roomId, p3.getId());
        roomService.joinRoom(roomId, p4.getId());
        roomService.setReady(roomId, host.getId(), true);
        roomService.setReady(roomId, p2.getId(), true);
        roomService.setReady(roomId, p3.getId(), true);
        Room started = roomService.setReady(roomId, p4.getId(), true);
        assertThat(started.status()).isEqualTo(RoomStatus.IN_GAME);
        return roomId;
    }

    @Test
    void admin_can_force_abort_in_game_match() throws Exception {
        User host = newUser("ac_host");
        User admin = newUser("ac_admin");
        adminRoleRepo.save(new AdminRole(admin.getId(), clock.instant()));
        String roomId = inGameRoom(host);

        mockMvc.perform(post("/api/admin/rooms/" + roomId + "/abort")
                .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.FINISHED);
    }

    @Test
    void non_admin_cannot_abort_and_match_unchanged() throws Exception {
        User host = newUser("ac_host"); // host 는 어드민 아님
        String roomId = inGameRoom(host);

        mockMvc.perform(post("/api/admin/rooms/" + roomId + "/abort")
                .header("Authorization", bearer(host)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        assertThat(roomService.getRoom(roomId).status()).isEqualTo(RoomStatus.IN_GAME);
    }
}
