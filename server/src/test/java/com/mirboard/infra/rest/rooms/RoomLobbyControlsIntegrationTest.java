package com.mirboard.infra.rest.rooms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-290 — 대기실 방장 기능의 REST 계약: {@code POST /bots?count=1}·{@code DELETE /bots/{userId}}, 대기실에서의 {@code POST /kick}
 * 과 재입장 {@code KICKED_FROM_ROOM}(403), {@code PUT /settings} 의 {@code capacity} 와 응답의 정원 범위.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-lobby-controls-http-test-secret-32-bytes-min"
})
class RoomLobbyControlsIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("봇 하나씩 넣고 빼기 · 정원 조절 · 대기실 강퇴와 재입장 금지")
    void host_controls_in_the_waiting_room() throws Exception {
        String host = registerAndLogin("lobbyhost1", "validpass1");
        String friend = registerAndLogin("lobbypal1", "validpass1");
        long friendId = userId(friend);

        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "대기실", "gameType", "ONE_CARD", "capacity", 6))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(6))
                .andExpect(jsonPath("$.minCapacity").value(2))
                .andExpect(jsonPath("$.maxCapacity").value(6))
                .andReturn();
        String roomId = json(created).get("roomId").asText();
        String base = "/api/rooms/" + roomId;

        // ── 봇 하나씩 ──
        mockMvc.perform(post(base + "/bots").param("count", "1").header("Authorization", "Bearer " + host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerCount").value(2))
                .andExpect(jsonPath("$.botSeats.length()").value(1));
        MvcResult two = mockMvc.perform(post(base + "/bots").param("count", "1").header("Authorization", "Bearer " + host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botSeats.length()").value(2))
                .andReturn();
        long secondBot = json(two).get("playerIds").get(2).asLong();
        mockMvc.perform(post(base + "/bots").param("count", "0").header("Authorization", "Bearer " + host))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ROOM_SETTING"));
        // 손님은 봇을 넣지도 빼지도 못한다.
        mockMvc.perform(post(base + "/join").header("Authorization", "Bearer " + friend)).andExpect(status().isOk());
        mockMvc.perform(post(base + "/bots").param("count", "1").header("Authorization", "Bearer " + friend))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_HOST"));
        mockMvc.perform(delete(base + "/bots/" + secondBot).header("Authorization", "Bearer " + friend))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(base + "/bots/" + secondBot).header("Authorization", "Bearer " + host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerCount").value(3))
                .andExpect(jsonPath("$.botSeats.length()").value(1));
        // 봇 빼기로 사람은 못 뺀다.
        mockMvc.perform(delete(base + "/bots/" + friendId).header("Authorization", "Bearer " + host))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_IN_ROOM"));

        // ── 정원 조절 ── 지금 3명(방장·봇·친구): 2 로는 못 줄이고, 7 은 게임 범위 밖, 3 은 된다.
        capacity(host, base, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_PLAYERS"));
        capacity(host, base, 7).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CAPACITY"));
        capacity(friend, base, 3).andExpect(status().isForbidden());
        capacity(host, base, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.maxCapacity").value(6));
        // 꽉 찼다 — 봇을 더 넣으면 ROOM_FULL.
        mockMvc.perform(post(base + "/bots").param("count", "1").header("Authorization", "Bearer " + host))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_FULL"));

        // ── 대기실 강퇴 ── 손님은 못 하고, 방장이 내보내면 좌석에서 빠지고 같은 링크로 돌아오지 못한다.
        mockMvc.perform(kick(base, friend, userId(host))).andExpect(status().isForbidden());
        mockMvc.perform(kick(base, host, friendId)).andExpect(status().isNoContent());
        mockMvc.perform(get(base).header("Authorization", "Bearer " + host))
                .andExpect(jsonPath("$.playerCount").value(2))
                .andExpect(jsonPath("$.status").value("WAITING"));
        mockMvc.perform(post(base + "/join-or-reconnect").header("Authorization", "Bearer " + friend))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("KICKED_FROM_ROOM"));
        mockMvc.perform(post(base + "/spectate").header("Authorization", "Bearer " + friend))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("KICKED_FROM_ROOM"));
    }

    private org.springframework.test.web.servlet.RequestBuilder kick(String base, String token, long target)
            throws Exception {
        return post(base + "/kick")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("userId", target)));
    }

    private org.springframework.test.web.servlet.ResultActions capacity(String token, String base, int capacity)
            throws Exception {
        return mockMvc.perform(put(base + "/settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("capacity", capacity))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long userId(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json(me).get("userId").asLong();
    }

    private String registerAndLogin(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
