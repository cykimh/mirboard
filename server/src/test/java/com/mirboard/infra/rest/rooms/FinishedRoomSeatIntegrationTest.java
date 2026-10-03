package com.mirboard.infra.rest.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.lobby.room.RoomRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * D-122 — 시작된 게임의 좌석 인덱스는 불변이다.
 *
 * <p>좌석 번호 = {@code room:{id}:players} 의 인덱스다. 매치가 끝난(FINISHED) 방에서 누가
 * 나갈 때 {@code LREM} 이 목록을 당기면, 남은 사람의 {@code indexOf} 가 다른 좌석을 가리켜
 * 종료 패널 이름이 밀리고 resync 가 <b>남의 비공개 뷰</b>(손패·미공개 예측)를 돌려줬다 —
 * State Hiding 위반. 고정하는 것:
 * <ol>
 *   <li>FINISHED 방 나가기는 좌석 목록·호스트를 건드리지 않고, 빈 방이 돼도 지우지 않는다
 *       (방은 {@code room_finish.lua} 의 600s TTL 로 사라진다).</li>
 *   <li>진행 중(IN_GAME) 매치에서 탈주가 처리되지 않은 '나가기'(이미 탈주한 좌석의 재요청,
 *       락 획득 실패)도 좌석을 당기지 않는다 — 라이브 STOMP 의 좌석 판정·비공개 이벤트
 *       라우팅이 같은 인덱스를 쓴다.</li>
 *   <li>나간 사람은 그 방에 묶이지 않는다 — 허브로 돌아가 다른 방에 들어갈 수 있다.</li>
 *   <li>심층 방어: 어떤 경로로든 시작 뒤 좌석 목록이 줄었으면 resync 는 비공개 뷰를 주지
 *       않는다(관전자 뷰). 목록에 없는 사람도 관전자 뷰다.</li>
 * </ol>
 *
 * <p>강제 종료(abort)로 FINISHED 를 만든다 — 1라운드 Bidding 도중이라 손패가 남아 있어,
 * 좌석이 틀어지면 남의 손패가 실제로 실린다(유출 재현 조건).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=finished-seat-test-secret-must-be-32-bytes-or-more"
})
class FinishedRoomSeatIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

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
    @Autowired RoomRepository roomRepository;

    // ---------- FINISHED 방 나가기 ----------

    @Test
    void leaving_a_finished_room_keeps_the_seats_so_others_resync_their_own_seat()
            throws Exception {
        Table t = startSkullKing("fs1");
        JsonNode bobBefore = resync(t.roomId, t.token(1)).get("privateHand");
        JsonNode carolBefore = resync(t.roomId, t.token(2)).get("privateHand");
        abort(t);

        leave(t.roomId, t.token(0)); // 호스트(좌석 0)가 먼저 '메인으로'.

        JsonNode room = getRoom(t.roomId, t.token(1));
        assertThat(room.get("status").asText()).isEqualTo("FINISHED");
        assertThat(longs(room.get("playerIds")))
                .as("좌석 목록 불변 — LREM 하지 않는다")
                .containsExactlyElementsOf(t.userIds);
        assertThat(room.get("hostId").asLong())
                .as("호스트 승격 없음")
                .isEqualTo(t.userIds.get(0));

        JsonNode bob = resync(t.roomId, t.token(1)).get("privateHand");
        JsonNode carol = resync(t.roomId, t.token(2)).get("privateHand");
        assertThat(bob.get("seat").asInt()).isEqualTo(1);
        assertThat(bob.get("hand")).isEqualTo(bobBefore.get("hand"));
        assertThat(carol.get("seat").asInt()).isEqualTo(2);
        assertThat(carol.get("hand")).isEqualTo(carolBefore.get("hand"));
    }

    @Test
    void the_leaver_is_free_to_go_back_to_the_hub_and_join_another_room() throws Exception {
        Table t = startSkullKing("fs2");
        abort(t);
        leave(t.roomId, t.token(0));

        // 허브 목록·다른 방 입장. ALREADY_IN_ROOM 은 방 단위 검사라 다른 방에 영향이 없다.
        mockMvc.perform(get("/api/rooms").header("Authorization", bearer(t.token(0))))
                .andExpect(status().isOk());
        Player other = registerAndLogin("fs2_other");
        String otherRoom = createRoom(other.token(), "TICHU", null);
        mockMvc.perform(post("/api/rooms/" + otherRoom + "/join")
                        .header("Authorization", bearer(t.token(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerIds.length()").value(2));
        leave(otherRoom, t.token(0));
        // 자기 방을 새로 만드는 것도 문제없다.
        createRoom(t.token(0), "TICHU", null);
    }

    @Test
    void everyone_leaving_a_finished_room_leaves_it_to_expire() throws Exception {
        Table t = startSkullKing("fs3");
        abort(t);

        for (int seat = 0; seat < t.userIds.size(); seat++) {
            leave(t.roomId, t.token(seat));
        }

        // 빈 방 소멸이 아니라 TTL 로 사라진다 — 좌석 목록도 메타와 같은 수명으로 줄어든다.
        JsonNode room = getRoom(t.roomId, t.token(0));
        assertThat(room.get("status").asText()).isEqualTo("FINISHED");
        assertThat(longs(room.get("playerIds"))).containsExactlyElementsOf(t.userIds);
        Long playersTtl = redis.getExpire("room:" + t.roomId + ":players");
        assertThat(playersTtl).isNotNull().isBetween(1L, 600L);
    }

    @Test
    void a_waiting_room_leave_still_shrinks_the_seats_and_promotes_the_host() throws Exception {
        Player host = registerAndLogin("fs4_host");
        Player guest = registerAndLogin("fs4_guest");
        String roomId = createRoom(host.token(), "SKULL_KING", 3);
        mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                        .header("Authorization", bearer(guest.token())))
                .andExpect(status().isOk());

        leave(roomId, host.token());

        JsonNode room = getRoom(roomId, guest.token());
        assertThat(longs(room.get("playerIds"))).containsExactly(guest.userId());
        assertThat(room.get("hostId").asLong()).isEqualTo(guest.userId());
    }

    // ---------- IN_GAME: 탈주가 처리되지 않은 '나가기' ----------

    /**
     * 사람 3인 스컬킹에서 좌석 0 이 '나가기' → 탈주(남은 2명이 계속, MATCH_CONTINUES). 같은
     * 사람이 다시 '나가기'(더블클릭, 또는 유예 탈주 뒤 재접속해 누름)하면 엔진은 이미 탈주한
     * 좌석이라 NOT_APPLICABLE 이다. 예전엔 일반 leave 로 흘러 IN_GAME 의 {@code LREM} 이 좌석을
     * 당겼다 — 라이브 매치 도중이라 carol 이 bob 의 손패로 카드를 내고, 다음 라운드의 bob 손패
     * (HAND_DEALT)가 carol 에게 갔다.
     */
    @Test
    void leaving_again_from_a_deserted_seat_keeps_the_seats_of_the_live_match()
            throws Exception {
        Table t = startSkullKing("fs7");
        JsonNode bobBefore = resync(t.roomId, t.token(1)).get("privateHand");
        JsonNode carolBefore = resync(t.roomId, t.token(2)).get("privateHand");

        leave(t.roomId, t.token(0)); // 탈주 — 남은 2명이 계속.
        leave(t.roomId, t.token(0)); // 이미 탈주한 좌석의 재요청.

        JsonNode room = getRoom(t.roomId, t.token(1));
        assertThat(room.get("status").asText()).isEqualTo("IN_GAME");
        assertThat(longs(room.get("playerIds")))
                .as("라이브 매치의 좌석 목록 불변 — LREM 하지 않는다")
                .containsExactlyElementsOf(t.userIds);
        assertThat(room.get("hostId").asLong()).isEqualTo(t.userIds.get(0));

        JsonNode bob = resync(t.roomId, t.token(1)).get("privateHand");
        JsonNode carol = resync(t.roomId, t.token(2)).get("privateHand");
        assertThat(bob.get("seat").asInt()).isEqualTo(1);
        assertThat(bob.get("hand")).isEqualTo(bobBefore.get("hand"));
        assertThat(carol.get("seat").asInt()).isEqualTo(2);
        assertThat(carol.get("hand")).isEqualTo(carolBefore.get("hand"));

        // 탈주한 사람은 이 방에 묶이지 않는다 — 다른 방 입장 정상.
        Player other = registerAndLogin("fs7_other");
        String otherRoom = createRoom(other.token(), "TICHU", null);
        mockMvc.perform(post("/api/rooms/" + otherRoom + "/join")
                        .header("Authorization", bearer(t.token(0))))
                .andExpect(status().isOk());
    }

    /**
     * 방 액션 락을 못 잡아(다른 처리가 오래 쥠) 탈주 처리가 건너뛰어진 '나가기'도 좌석을 당기지
     * 않는다. 좌석은 그대로 두고, 그 사람의 끊김은 유예 탈주(120s)가 처리한다.
     */
    @Test
    void a_leave_that_cannot_take_the_room_lock_keeps_the_seat() throws Exception {
        Table t = startSkullKing("fs8");
        String lockKey = "room:" + t.roomId + ":lock";
        redis.opsForValue().set(lockKey, "held-by-test", java.time.Duration.ofSeconds(30));
        try {
            leave(t.roomId, t.token(0)); // 탈주 처리 락 재시도(~3s) 실패.
        } finally {
            redis.delete(lockKey);
        }

        JsonNode room = getRoom(t.roomId, t.token(1));
        assertThat(room.get("status").asText()).isEqualTo("IN_GAME");
        assertThat(longs(room.get("playerIds"))).containsExactlyElementsOf(t.userIds);
        assertThat(resync(t.roomId, t.token(2)).get("privateHand").get("seat").asInt())
                .isEqualTo(2);
    }

    // ---------- resync 심층 방어 ----------

    /**
     * 시작 뒤 좌석 목록이 줄어든 방(IN_GAME 의 leave 폴백 — 티츄 리매치 대기 등, D-122 가 별건으로
     * 남긴 경로)을 저장소로 직접 만든다. 남은 사람의 {@code indexOf} 는 당겨진 번호라, 그대로
     * 쓰면 좌석 0(나간 사람)의 손패가 나간다.
     */
    @Test
    void a_shrunk_seat_list_never_hands_out_another_seats_private_view() throws Exception {
        Table t = startSkullKing("fs5");
        JsonNode aliceHand = resync(t.roomId, t.token(0)).get("privateHand").get("hand");

        roomRepository.leave(t.roomId, t.userIds.get(0)); // LREM — bob 이 인덱스 0 이 된다.

        JsonNode bob = resync(t.roomId, t.token(1));
        assertThat(bob.get("tableView").isNull()).isFalse();
        assertThat(bob.get("privateHand").isNull())
                .as("좌석을 확신할 수 없으면 관전자 뷰 — 앨리스의 손패 %s 가 나가면 안 된다",
                        aliceHand)
                .isTrue();
    }

    @Test
    void a_user_no_longer_in_the_seat_list_gets_the_spectator_view() throws Exception {
        Table t = startSkullKing("fs6");
        roomRepository.leave(t.roomId, t.userIds.get(0));
        mockMvc.perform(post("/api/rooms/" + t.roomId + "/spectate")
                        .header("Authorization", bearer(t.token(0))))
                .andExpect(status().isOk());

        JsonNode alice = resync(t.roomId, t.token(0));

        assertThat(alice.get("tableView").isNull()).isFalse();
        assertThat(alice.get("privateHand").isNull()).isTrue();
    }

    // ---------- helpers ----------

    /** 사람 3명 스컬킹 방을 시작한다(1라운드 Bidding, 각자 손패 1장). */
    private Table startSkullKing(String prefix) throws Exception {
        List<Player> players = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            players.add(registerAndLogin(prefix + "_" + name));
        }
        String roomId = createRoom(players.get(0).token(), "SKULL_KING", 3);
        for (int i = 1; i < players.size(); i++) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/join")
                            .header("Authorization", bearer(players.get(i).token())))
                    .andExpect(status().isOk());
        }
        for (Player p : players) {
            mockMvc.perform(post("/api/rooms/" + roomId + "/ready")
                            .header("Authorization", bearer(p.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ready\":true}"))
                    .andExpect(status().isOk());
        }
        List<Long> ids = players.stream().map(Player::userId).toList();
        JsonNode room = getRoom(roomId, players.get(0).token());
        assertThat(room.get("status").asText()).isEqualTo("IN_GAME");
        assertThat(longs(room.get("playerIds"))).containsExactlyElementsOf(ids);
        return new Table(roomId, players.stream().map(Player::token).toList(), ids);
    }

    private void abort(Table t) throws Exception {
        mockMvc.perform(post("/api/rooms/" + t.roomId + "/abort")
                        .header("Authorization", bearer(t.token(0))))
                .andExpect(status().isNoContent());
    }

    private void leave(String roomId, String token) throws Exception {
        mockMvc.perform(post("/api/rooms/" + roomId + "/leave")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    private JsonNode resync(String roomId, String token) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/rooms/" + roomId + "/resync")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode getRoom(String roomId, String token) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/rooms/" + roomId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private String createRoom(String token, String gameType, Integer capacity) throws Exception {
        Map<String, Object> body = capacity == null
                ? Map.of("name", "seat-room", "gameType", gameType)
                : Map.of("name", "seat-room", "gameType", gameType, "capacity", capacity);
        MvcResult created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("roomId").asText();
    }

    private Player registerAndLogin(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "validpass1"));
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        return new Player(json.get("accessToken").asText(),
                json.get("user").get("userId").asLong());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private record Player(String token, long userId) {
    }

    private record Table(String roomId, List<String> tokens, List<Long> userIds) {
        String token(int seat) {
            return tokens.get(seat);
        }
    }
}
