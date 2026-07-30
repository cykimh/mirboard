package com.mirboard.infra.rest.rooms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
 * D-99 (S2) — 방 인원 가변. 스컬킹이 아직 없으므로 인원 가변 게임은 테스트 전용 fake
 * {@link GameDefinition}(min 2 / max 8)으로 검증한다. 티츄(min=max=4)는 요청 본문·응답이
 * 무변경이어야 하며, 그 증거는 본 클래스의 티츄 케이스 + 기존 방 생성 IT 전량 그린이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-capacity-test-secret-must-be-32-bytes-or-more"
})
class RoomCapacityIntegrationTest {

    /** 인원 가변 게임(2~8). 스컬킹의 자리를 대신하는 테스트 전용 정의. */
    private static final String VARIABLE_GAME = "CAPACITY_TEST";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @TestConfiguration
    static class VariableCapacityGameConfig {
        @Bean
        GameDefinition variableCapacityGame() {
            return new VariableCapacityGame();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void variable_game_uses_requested_capacity() throws Exception {
        String token = registerAndLogin("cap_pick_user", "validpass1");

        mockMvc.perform(create(token, Map.of(
                        "name", "4인 방", "gameType", VARIABLE_GAME, "capacity", 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.playerCount").value(1));
    }

    @Test
    void variable_game_without_capacity_defaults_to_max_players() throws Exception {
        String token = registerAndLogin("cap_default_user", "validpass1");

        // 현행 호환 — capacity 미지정이면 def.maxPlayers().
        mockMvc.perform(create(token, Map.of(
                        "name", "기본 인원 방", "gameType", VARIABLE_GAME)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(8));
    }

    @Test
    void variable_game_accepts_min_and_max_bounds() throws Exception {
        String token = registerAndLogin("cap_bounds_user", "validpass1");

        mockMvc.perform(create(token, Map.of(
                        "name", "최소 인원", "gameType", VARIABLE_GAME, "capacity", 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(2));

        mockMvc.perform(create(token, Map.of(
                        "name", "최대 인원", "gameType", VARIABLE_GAME, "capacity", 8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(8));
    }

    @Test
    void capacity_above_max_players_is_rejected() throws Exception {
        String token = registerAndLogin("cap_over_user", "validpass1");

        mockMvc.perform(create(token, Map.of(
                        "name", "9인 방", "gameType", VARIABLE_GAME, "capacity", 9)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CAPACITY"));
    }

    @Test
    void capacity_below_min_players_is_rejected() throws Exception {
        String token = registerAndLogin("cap_under_user", "validpass1");

        mockMvc.perform(create(token, Map.of(
                        "name", "1인 방", "gameType", VARIABLE_GAME, "capacity", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CAPACITY"));
    }

    @Test
    void fixed_game_accepts_its_only_capacity() throws Exception {
        String token = registerAndLogin("cap_tichu_ok_user", "validpass1");

        // 티츄는 min=max=4 — 명시해도 통과하고 응답은 기존과 동일.
        mockMvc.perform(create(token, Map.of(
                        "name", "티츄 4인", "gameType", "TICHU", "capacity", 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(4));
    }

    @Test
    void fixed_game_rejects_other_capacity() throws Exception {
        String token = registerAndLogin("cap_tichu_bad_user", "validpass1");

        mockMvc.perform(create(token, Map.of(
                        "name", "티츄 2인", "gameType", "TICHU", "capacity", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CAPACITY"));
    }

    @Test
    void fill_with_bots_fills_up_to_requested_capacity_not_max_players() throws Exception {
        String token = registerAndLogin("cap_bots_user", "validpass1");

        // capacity 3 → 호스트 1 + 봇 2. maxPlayers(8) 를 따르면 봇 7 을 시도해 실패한다.
        mockMvc.perform(create(token, Map.of(
                        "name", "3인 봇 방", "gameType", VARIABLE_GAME,
                        "capacity", 3, "fillWithBots", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.playerCount").value(3))
                .andExpect(jsonPath("$.botSeats.length()").value(2));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            String token, Map<String, Object> body) throws Exception {
        return post("/api/rooms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        var body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    /**
     * 인원 가변 게임의 스탠드인. 엔진은 만들지 않는다 — 본 IT 는 방 생성 계약만 검증하고
     * 게임을 시작시키지 않는다(사람 호스트가 ready 를 누르지 않음).
     */
    private static final class VariableCapacityGame implements GameDefinition {
        @Override
        public String id() {
            return VARIABLE_GAME;
        }

        @Override
        public String displayName() {
            return "인원 가변 테스트 게임";
        }

        @Override
        public String shortDescription() {
            return "(D-99 테스트 스탠드인 — 2~8인)";
        }

        @Override
        public int minPlayers() {
            return 2;
        }

        @Override
        public int maxPlayers() {
            return 8;
        }

        @Override
        public GameStatus status() {
            return GameStatus.AVAILABLE;
        }

        @Override
        public GameEngine newEngine(GameContext ctx) {
            throw new UnsupportedOperationException("테스트 스탠드인 — 엔진 없음");
        }
    }
}
