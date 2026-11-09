package com.mirboard.infra.rest.rooms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.TeamModeTestGame;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-176 — 맵 방 옵션이 실제 Redis·Lua 로 끝까지 간다: 만들 때 기본값({@code opt:<NAME>}), 대기실 설정으로 모드를 바꾸면 정원이
 * 2 ↔ 4 로 바뀌고, 정원 2 방에 셋째는 못 앉고, 2:2 에서 3명 시작은 거절, 3명일 때 1:1 로 줄이기는 TOO_MANY_PLAYERS. 게임은 테스트
 * 전용 {@link TeamModeTestGame}(오목 자리) — 게임을 실제로 시작하지는 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-options-test-secret-must-be-32-bytes-or-more"
})
class RoomOptionsIntegrationTest {

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
    static class TeamModeGameConfig {
        @Bean
        GameDefinition teamModeTestGame() {
            return new TeamModeTestGame();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("모드 1:1 ↔ 2:2 로 정원이 2 ↔ 4, 셋째 입장·3명 시작·인원 초과 축소는 거절")
    void team_mode_drives_capacity_and_start() throws Exception {
        String host = registerAndLogin("optmodeh1", "validpass1");
        String second = registerAndLogin("optmodeh2", "validpass1");
        String third = registerAndLogin("optmodeh3", "validpass1");

        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "모드 방", "gameType", TeamModeTestGame.ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.options.TEAM_MODE").value("SOLO"))
                .andExpect(jsonPath("$.options.BOT_LEVEL").value("NORMAL"))
                .andReturn();
        String roomId = objectMapper.readTree(created.getResponse().getContentAsString()).get("roomId").asText();
        org.assertj.core.api.Assertions.assertThat(redis.opsForHash().get("room:" + roomId, "opt:TEAM_MODE"))
                .isEqualTo("SOLO");

        join(second, roomId).andExpect(status().isOk());
        // 1:1 정원 2 — 셋째는 앉지 못한다(room_join.lua 의 capacity).
        join(third, roomId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_FULL"));

        // 2:2 로 — 정원 4, 이제 셋째가 앉는다.
        settings(host, roomId, Map.of("TEAM_MODE", "TEAMS_2V2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.options.TEAM_MODE").value("TEAMS_2V2"));
        join(third, roomId).andExpect(status().isOk());

        // 2:2 는 정확히 4명 — 3명 시작은 NOT_ENOUGH_PLAYERS(room_start_now.lua 에 시작 인원 4).
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", "Bearer " + host))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_ENOUGH_PLAYERS"))
                .andExpect(jsonPath("$.error.details.minPlayers").value(4));

        // 3명인데 1:1(정원 2)로 — TOO_MANY_PLAYERS, 방은 그대로.
        settings(host, roomId, Map.of("TEAM_MODE", "SOLO"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_PLAYERS"))
                .andExpect(jsonPath("$.error.details.capacity").value(2));
        mockMvc.perform(get("/api/rooms/" + roomId).header("Authorization", "Bearer " + host))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.options.TEAM_MODE").value("TEAMS_2V2"));

        // 선언하지 않은 옵션(오목 룰)에 기본 아닌 값 → UNSUPPORTED_ROOM_OPTION, 허용값 밖 → INVALID_ROOM_SETTING.
        settings(host, roomId, Map.of("OMOK_RULE", "FREESTYLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ROOM_OPTION"))
                .andExpect(jsonPath("$.error.details.option").value("OMOK_RULE"));
        settings(host, roomId, Map.of("BOT_LEVEL", "GODLIKE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ROOM_SETTING"));
    }

    @Test
    @DisplayName("카탈로그는 선언한 맵 옵션의 선택지·기본값을 싣고, 세 게임은 빈 목록")
    void catalog_carries_option_choices() throws Exception {
        String token = registerAndLogin("optcatal1", "validpass1");
        mockMvc.perform(get("/api/games/" + TeamModeTestGame.ID).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomOptionChoices[0].option").value("TEAM_MODE"))
                .andExpect(jsonPath("$.roomOptionChoices[0].values[1]").value("TEAMS_2V2"))
                .andExpect(jsonPath("$.roomOptionChoices[0].defaultValue").value("SOLO"))
                .andExpect(jsonPath("$.roomOptionChoices[1].option").value("BOT_LEVEL"));
        mockMvc.perform(get("/api/games/TICHU").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.roomOptionChoices.length()").value(0));
    }

    private ResultActions join(String token, String roomId) throws Exception {
        return mockMvc.perform(post("/api/rooms/" + roomId + "/join").header("Authorization", "Bearer " + token));
    }

    private ResultActions settings(String token, String roomId, Map<String, String> options) throws Exception {
        return mockMvc.perform(put("/api/rooms/" + roomId + "/settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("options", options))));
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
