package com.mirboard.infra.rest.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.lobby.room.RoomRepository;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.RoomDisconnectHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * D-136 — 방 만들기 두 갈래(통합). 봇과 연습(`fillWithBots=true`)은 방장 + 봇으로 만들어 곧바로 IN_GAME 이 되고 대기 중인 방 목록
 * (`rooms:open`)에 한 번도 오르지 않는다. 친구와 하기 대기실은 방장이 "지금 N명으로 시작"(`POST /start`) — `room_start_now.lua` 가 정원을
 * 현재 인원으로 줄이고 시작 전이를 한 번에 해서, 시작 뒤 모든 좌석이 비공개 손패를 받는다(`occupiedSeat` 는 정원 = 인원을 본다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-modes-test-secret-must-be-32-bytes-or-more"
})
class RoomModesIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redis;
    @Autowired RoomRepository repository;
    @Autowired RoomDisconnectHandler disconnects;

    private final Map<String, Long> userIds = new HashMap<>();

    // ---------- 봇과 연습 ----------

    @Test
    void a_practice_room_starts_right_away_and_is_not_listed() throws Exception {
        String host = registerAndLogin("rmphost");

        JsonNode room = create(host, Map.of("name", "연습", "gameType", "ONE_CARD", "fillWithBots", true, "capacity", 4));

        assertThat(room.get("status").asText()).isEqualTo("IN_GAME");
        assertThat(room.get("fillWithBots").asBoolean()).isTrue();
        assertThat(ints(room.get("botSeats"))).containsExactly(1, 2, 3);
        assertThat(waitingRoomIds(host)).doesNotContain(room.get("roomId").asText());
        mockMvc.perform(get("/api/rooms/" + room.get("roomId").asText() + "/resync")
                        .header("Authorization", bearer(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privateHand").exists());
    }

    @Test
    void practice_starts_right_away_for_every_game() throws Exception {
        String host = registerAndLogin("rmpall");
        for (String game : List.of("TICHU", "SKULL_KING")) {
            JsonNode room = create(host, Map.of("name", "연습 " + game, "gameType", game, "fillWithBots", true));
            assertThat(room.get("status").asText()).as(game).isEqualTo("IN_GAME");
        }
    }

    /** 만드는 Lua 한 번 안에서 목록에 넣지 않는다 — 시작 전이가 지우기 전의 한순간에도 목록에 없다. */
    @Test
    void a_practice_room_never_enters_the_open_set() {
        String practice = UUID.randomUUID().toString();
        String friends = UUID.randomUUID().toString();

        repository.create(practice, 1L, "연습", "ONE_CARD", 4, 1L, TeamPolicy.SEQUENTIAL, true, 1000, 0, 0);
        repository.create(friends, 1L, "친구", "ONE_CARD", 4, 1L, TeamPolicy.SEQUENTIAL, false, 1000, 0, 0);

        assertThat(redis.opsForZSet().score("rooms:open", practice)).isNull();
        assertThat(redis.opsForZSet().score("rooms:open", friends)).isNotNull();
    }

    @Test
    void a_friends_room_has_no_bots_and_waits() throws Exception {
        String host = registerAndLogin("rmfhost");

        JsonNode room = create(host, Map.of("name", "친구", "gameType", "ONE_CARD", "capacity", 4));

        assertThat(room.get("status").asText()).isEqualTo("WAITING");
        assertThat(ints(room.get("botSeats"))).isEmpty();
        assertThat(waitingRoomIds(host)).contains(room.get("roomId").asText());
    }

    // ---------- 방장 시작 (D-143 — 준비 없음, 정원이 차도 자동 시작 없음) ----------

    @Test
    void the_host_starts_with_three_of_four_and_every_seat_gets_a_hand() throws Exception {
        List<String> players = List.of(registerAndLogin("rmshost"), registerAndLogin("rmsa"), registerAndLogin("rmsb"));
        String roomId = friendsRoom(players, "ONE_CARD", 6);

        // 아무도 준비하지 않았다 — 준비는 시작 조건이 아니다.
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.playerCount").value(3));

        assertThat(waitingRoomIds(players.get(0))).doesNotContain(roomId);
        for (String token : players) {
            mockMvc.perform(get("/api/rooms/" + roomId + "/resync").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privateHand").exists());
        }
    }

    @Test
    void the_host_alone_cannot_start() throws Exception {
        String host = registerAndLogin("rmalone");
        String roomId = friendsRoom(List.of(host), "ONE_CARD", 6);

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_ENOUGH_PLAYERS"));
    }

    @Test
    void only_the_host_starts() throws Exception {
        List<String> players = List.of(registerAndLogin("rmnhhost"), registerAndLogin("rmnha"));
        String roomId = friendsRoom(players, "ONE_CARD", 6);

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_HOST"));
    }

    @Test
    void tichu_needs_all_four() throws Exception {
        List<String> players = List.of(registerAndLogin("rmthost"), registerAndLogin("rmta"), registerAndLogin("rmtb"));
        String roomId = friendsRoom(players, "TICHU", null);

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_ENOUGH_PLAYERS"));
    }

    /** 티츄도 방장이 시작한다 — 4명이 다 모여도 저절로 시작하지 않는다. */
    @Test
    void tichu_with_all_four_starts_by_the_host() throws Exception {
        List<String> players = List.of(registerAndLogin("rmt4host"), registerAndLogin("rmt4a"),
                registerAndLogin("rmt4b"), registerAndLogin("rmt4c"));
        String roomId = friendsRoom(players, "TICHU", null);
        assertThat(roomStatus(roomId, players.get(0))).isEqualTo("WAITING");

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"))
                .andExpect(jsonPath("$.capacity").value(4));
    }

    /**
     * D-143 의 대가 — 대기실에서 끊긴 사람(D-134 준비 해제·유예 중)이 있어도 방장은 시작할 수 있다. 그 사람은 게임 중 끊김 유예를 탄다.
     */
    @Test
    void a_disconnected_player_does_not_block_the_host_start() throws Exception {
        List<String> players = List.of(registerAndLogin("rmdchost"), registerAndLogin("rmdca"), registerAndLogin("rmdcb"));
        String roomId = friendsRoom(players, "ONE_CARD", 6);

        disconnects.onDisconnect(roomId, userIds.get(players.get(2)));

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerCount").value(3));
    }

    @Test
    void a_full_room_waits_for_the_host() throws Exception {
        List<String> players = List.of(registerAndLogin("rmfullhost"), registerAndLogin("rmfulla"));
        String roomId = friendsRoom(players, "ONE_CARD", 2);
        assertThat(roomStatus(roomId, players.get(0))).isEqualTo("WAITING");

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"))
                .andExpect(jsonPath("$.capacity").value(2));
    }

    /** D-161 — 친구 방의 준비는 표시만 한다. 정원이 다 차고 모두 준비해도 시작하지 않고, 방장이 시작한다. */
    @Test
    void a_full_friends_room_with_everyone_ready_still_waits_for_the_host() throws Exception {
        List<String> players = List.of(registerAndLogin("rmrdyhost"), registerAndLogin("rmrdya"));
        String roomId = friendsRoom(players, "ONE_CARD", 2);
        for (String token : players) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/ready").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                    .andExpect(status().isOk());
        }

        assertThat(roomStatus(roomId, players.get(0))).isEqualTo("WAITING");
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", bearer(players.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_GAME"));
    }

    /** D-161 — 대기실에서 방장이 게임 설정을 바꾼다. 게임이 안 쓰는 설정·방장 아닌 사람·시작된 방은 거절한다. */
    @Test
    void the_host_changes_settings_in_the_waiting_room() throws Exception {
        List<String> players = List.of(registerAndLogin("rmsethost"), registerAndLogin("rmseta"));
        String roomId = friendsRoom(players, "ONE_CARD", null);
        String host = bearer(players.get(0));

        mockMvc.perform(put("/api/rooms/" + roomId + "/settings").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"turnSeconds\":10,\"raceButtonSize\":\"LARGE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnSeconds").value(10))
                .andExpect(jsonPath("$.raceButtonSize").value("LARGE"));
        mockMvc.perform(get("/api/rooms/" + roomId).header("Authorization", host))
                .andExpect(jsonPath("$.raceButtonSize").value("LARGE"));

        mockMvc.perform(put("/api/rooms/" + roomId + "/settings").header("Authorization", bearer(players.get(1)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"turnSeconds\":5}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/rooms/" + roomId + "/settings").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stake\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ROOM_OPTION"));
        mockMvc.perform(put("/api/rooms/" + roomId + "/settings").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"raceButtonSize\":\"HUGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ROOM_SETTING"));

        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", host))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/rooms/" + roomId + "/settings").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"turnSeconds\":5}"))
                .andExpect(status().isConflict());
    }

    /** D-162 — 게임 중 끊긴 사람(이 테스트의 손님은 소켓을 연 적이 없다)은 방장이 바로 탈주 처리한다. 원카드는 남은 사람끼리 계속한다. */
    @Test
    void the_host_kicks_a_disconnected_player_and_the_match_goes_on() throws Exception {
        List<String> players = List.of(registerAndLogin("rmkickhost"), registerAndLogin("rmkicka"), registerAndLogin("rmkickb"));
        String roomId = friendsRoom(players, "ONE_CARD", null);
        String host = bearer(players.get(0));
        mockMvc.perform(post("/api/rooms/" + roomId + "/start").header("Authorization", host)).andExpect(status().isOk());
        long target = userIds.get(players.get(1));

        mockMvc.perform(post("/api/rooms/" + roomId + "/kick").header("Authorization", bearer(players.get(2)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + target + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rooms/" + roomId + "/kick").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + userIds.get(players.get(0)) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_IN_ROOM"));

        mockMvc.perform(post("/api/rooms/" + roomId + "/kick").header("Authorization", host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + target + "}"))
                .andExpect(status().isNoContent());
        assertThat(roomStatus(roomId, players.get(0))).isEqualTo("IN_GAME");
        mockMvc.perform(get("/api/rooms/" + roomId + "/resync").header("Authorization", host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableView.seats[1].eliminated").value("DESERTED"));
    }

    // ---------- helpers ----------

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString());
    }

    /** 첫 토큰이 방장. 나머지는 입장만(준비는 하지 않는다). */
    private String friendsRoom(List<String> tokens, String gameType, Integer capacity) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "친구 방", "gameType", gameType));
        if (capacity != null) {
            body.put("capacity", capacity);
        }
        String roomId = create(tokens.get(0), body).get("roomId").asText();
        for (int i = 1; i < tokens.size(); i++) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/join").header("Authorization", bearer(tokens.get(i))))
                    .andExpect(status().isOk());
        }
        return roomId;
    }

    private String roomStatus(String roomId, String token) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/rooms/" + roomId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("status").asText();
    }

    private List<String> waitingRoomIds(String token) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/rooms").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(r.getResponse().getContentAsString()).get("rooms")
                .forEach(room -> ids.add(room.get("roomId").asText()));
        return ids;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private String registerAndLogin(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        userIds.put(token, json.get("user").get("userId").asLong());
        return token;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
