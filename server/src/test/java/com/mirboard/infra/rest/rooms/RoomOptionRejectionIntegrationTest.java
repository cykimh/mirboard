package com.mirboard.infra.rest.rooms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * D-106 — 게임이 쓰지 않는 방 설정은 서버가 거절한다.
 *
 * <p>클라를 고쳐 안 보내게 했더라도 이 테스트가 필요하다. 클라 입력은 검증 대상이지 신뢰
 * 대상이 아니고(Server-Authoritative), 이 결함의 원인이 정확히 "조용히 통과시킨 것"이었다 —
 * 스컬킹 방에 stake 를 걸면 `RoomChipService` 가 return 해서 칩은 없는데 봇만 금지됐다.
 *
 * <p>기본값은 통과해야 한다. 예전 클라가 늘 `targetScore:1000` 을 보냈고, 그 값으로 만든
 * 방은 정상이기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-option-test-secret-must-be-32-bytes-or-more"
})
class RoomOptionRejectionIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── 거절 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스컬킹 방에 판돈을 걸면 거절한다 (칩 없이 봇만 금지되던 경로)")
    void rejectsBettingOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_bet_user", "validpass1");

        createRoom(token, Map.of(
                "name", "판돈 스컬킹", "gameType", "SKULL_KING", "stake", 100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ROOM_OPTION"))
                .andExpect(jsonPath("$.error.details.option").value("BETTING"))
                .andExpect(jsonPath("$.error.details.gameType").value("SKULL_KING"));
    }

    @Test
    @DisplayName("스컬킹 방에 목표 점수를 지정하면 거절한다 (10라운드 고정)")
    void rejectsTargetScoreOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_score_user", "validpass1");

        createRoom(token, Map.of(
                "name", "300점 스컬킹", "gameType", "SKULL_KING", "targetScore", 300))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ROOM_OPTION"))
                .andExpect(jsonPath("$.error.details.option").value("TARGET_SCORE"));
    }

    // 좌석 정책은 여기 없다 — 게임 중립이라 거절 대상이 아니다(D-106 정정). 아래 통과 절 참조.

    // ── 통과 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기본값은 통과한다 — 예전 클라가 보내던 targetScore:1000·stake:0")
    void acceptsDefaultsOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_default_user", "validpass1");

        createRoom(token, Map.of(
                "name", "기본 스컬킹", "gameType", "SKULL_KING",
                "targetScore", 1000, "stake", 0, "teamPolicy", "SEQUENTIAL"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("옵션을 아예 안 보내도 통과한다 (D-106 이후 클라)")
    void acceptsOmittedOptionsOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_omit_user", "validpass1");

        createRoom(token, Map.of("name", "옵션 없는 스컬킹", "gameType", "SKULL_KING"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("티츄는 세 옵션을 모두 받는다 — 게이팅이 과하게 먹지 않았는지")
    void acceptsAllOptionsOnTichu() throws Exception {
        String token = registerAndLogin("opt_tichu_user", "validpass1");

        createRoom(token, Map.of(
                "name", "티츄 내기방", "gameType", "TICHU",
                "targetScore", 500, "stake", 100, "teamPolicy", "RANDOM"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("개인전에도 RANDOM 좌석 정책을 받는다 — 좌석 순서 = 턴 순서 (D-106 정정)")
    void acceptsRandomSeatPolicyOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_seat_user", "validpass1");

        // 한때 이걸 UNSUPPORTED_ROOM_OPTION 으로 막았다. `domain.game` 에 TeamPolicy 참조가
        // 0건이고 RANDOM 은 onGameStart 에서 좌석 순서를 섞을 뿐이라, 동작하는 기능을
        // 없앤 것이었다.
        createRoom(token, Map.of(
                "name", "좌석 셔플 스컬킹", "gameType", "SKULL_KING", "teamPolicy", "RANDOM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamPolicy").value("RANDOM"));
    }

    @Test
    @DisplayName("만들어진 개인전 방의 좌석 정책도 바꿀 수 있다")
    void acceptsSeatPolicyUpdateOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_seat_up_user", "validpass1");
        String roomId = createdRoomId(token, Map.of(
                "name", "스컬킹 방", "gameType", "SKULL_KING"));

        mockMvc.perform(put("/api/rooms/" + roomId + "/team-policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("teamPolicy", "RANDOM"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamPolicy").value("RANDOM"));
    }

    @Test
    @DisplayName("게임 중립 옵션(턴 제한)은 스컬킹에서도 그대로 받는다")
    void acceptsNeutralOptionsOnSkullKing() throws Exception {
        String token = registerAndLogin("opt_neutral_user", "validpass1");

        createRoom(token, Map.of(
                "name", "턴제한 스컬킹", "gameType", "SKULL_KING", "turnSeconds", 60))
                .andExpect(status().isCreated());
    }

    // ── 카탈로그 노출 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("카탈로그가 게임별 supportedRoomOptions 를 노출한다 (클라 게이팅 근거)")
    void catalogExposesSupportedRoomOptions() throws Exception {
        String token = registerAndLogin("opt_catalog_user", "validpass1");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/games/TICHU").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportedRoomOptions",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "TARGET_SCORE", "TEAMS", "BETTING")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/games/SKULL_KING").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportedRoomOptions").isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions createRoom(
            String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/rooms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String createdRoomId(String token, Map<String, Object> body) throws Exception {
        MvcResult res = createRoom(token, body).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("roomId").asText();
    }

    private String registerAndLogin(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
