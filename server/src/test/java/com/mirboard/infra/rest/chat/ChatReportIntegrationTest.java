package com.mirboard.infra.rest.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.admin.AdminRole;
import com.mirboard.domain.admin.AdminRoleRepository;
import com.mirboard.domain.admin.ChatLogStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-93 — 채팅 신고 end-to-end.
 *
 * <p>가장 중요한 검증은 <b>클라가 본문을 위조할 수 없다</b>는 것이다: 신고 요청에는
 * eventId 만 들어가고, 적재된 `message` 는 서버가 링버퍼에서 확정한 값이어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=chat-report-test-secret-must-be-32-bytes-min"
})
class ChatReportIntegrationTest {

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
    @Autowired ObjectMapper objectMapper;
    @Autowired ChatLogStore chatLog;
    @Autowired AdminRoleRepository adminRoles;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flushRedis() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    private record User(long id, String token) {
    }

    private User register(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "correctpass1"));
        String reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(reg).get("userId").asLong();
        String login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new User(id, objectMapper.readTree(login).get("accessToken").asText());
    }

    /** 서버가 broadcast 했다고 가정하고 링버퍼에 직접 넣는다(STOMP 왕복 없이 신고 경로만 검증). */
    private String seedMessage(String roomId, long authorId, String authorName, String text) {
        String eventId = UUID.randomUUID().toString();
        chatLog.record(ChatLogStore.SCOPE_ROOM, roomId, new ChatLogStore.Entry(
                eventId, authorId, authorName, text, Instant.now().toEpochMilli()));
        return eventId;
    }

    private String reportBody(String eventId, String roomId) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("eventId", eventId, "scope", "ROOM", "roomId", roomId));
    }

    @Test
    void report_persists_the_server_side_message_not_what_the_client_claims() throws Exception {
        User author = register("crauthor1");
        User reporter = register("crreporter");
        String roomId = UUID.randomUUID().toString();
        String eventId = seedMessage(roomId, author.id(), "crauthor1", "서버가 보관한 진짜 본문");

        // 클라가 본문을 끼워 넣어도 서버는 무시해야 한다 — 요청에 message 를 넣어본다.
        String forged = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId, "scope", "ROOM", "roomId", roomId,
                "message", "조작된 본문", "reportedUserId", 9999));

        mockMvc.perform(post("/api/chat/reports")
                        .header("Authorization", "Bearer " + reporter.token())
                        .contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId));

        // 어드민 조회로 적재분 확인 — 본문/작성자 모두 서버 보관분이어야 한다.
        adminRoles.save(new AdminRole(reporter.id(), Instant.now()));
        mockMvc.perform(get("/api/admin/chat-reports")
                        .header("Authorization", "Bearer " + reporter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].message").value("서버가 보관한 진짜 본문"))
                .andExpect(jsonPath("$.reports[0].reportedUserId").value(author.id()))
                .andExpect(jsonPath("$.reports[0].reporterUserId").value(reporter.id()))
                .andExpect(jsonPath("$.reports[0].totalAgainstReported").value(1));
    }

    @Test
    void reporting_the_same_message_twice_is_rejected() throws Exception {
        User author = register("crauthor2");
        User reporter = register("crreporter2");
        String roomId = UUID.randomUUID().toString();
        String eventId = seedMessage(roomId, author.id(), "crauthor2", "도배 메시지");

        mockMvc.perform(post("/api/chat/reports")
                        .header("Authorization", "Bearer " + reporter.token())
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody(eventId, roomId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/chat/reports")
                        .header("Authorization", "Bearer " + reporter.token())
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody(eventId, roomId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_REPORT"));
    }

    @Test
    void reporting_your_own_message_is_rejected() throws Exception {
        User me = register("crself0001");
        String roomId = UUID.randomUUID().toString();
        String eventId = seedMessage(roomId, me.id(), "crself0001", "내 메시지");

        mockMvc.perform(post("/api/chat/reports")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody(eventId, roomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SELF_REPORT"));
    }

    @Test
    void reporting_an_unknown_or_expired_message_is_rejected() throws Exception {
        User reporter = register("crghost001");
        String roomId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/chat/reports")
                        .header("Authorization", "Bearer " + reporter.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody(UUID.randomUUID().toString(), roomId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_MESSAGE_NOT_FOUND"));
    }

    @Test
    void chat_report_list_requires_admin() throws Exception {
        User plain = register("crplain001");
        mockMvc.perform(get("/api/admin/chat-reports")
                        .header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
    }

    @Test
    void ring_buffer_keeps_only_the_most_recent_entries() {
        String roomId = UUID.randomUUID().toString();
        String oldest = seedMessage(roomId, 1L, "u1", "가장 오래된 메시지");
        for (int i = 0; i < 100; i++) {
            seedMessage(roomId, 1L, "u1", "flood-" + i);
        }
        // 100개 상한을 넘기면 가장 오래된 것부터 밀려난다 → 그 메시지는 신고 불가.
        assertThat(chatLog.find(ChatLogStore.SCOPE_ROOM, roomId, oldest)).isEmpty();
    }
}
