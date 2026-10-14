package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * D-133 — {@code GET /rooms/{roomId}} 방 미리보기. 공유 링크가 방 주소 그대로라, 카톡 스크래퍼가 그 주소를 긁으면 이 컨트롤러가
 * 빌드된 {@code index.html} 의 표식 구간을 그 방의 Open Graph 로 바꿔 준다. 브라우저에게는 같은 SPA 다(클라 라우터가 방 화면을 그린다).
 *
 * <p>스프링 컨텍스트 없이 컨트롤러만 MockMvc 로 세운다(Docker 불필요). 보안 필터 체인·SPA 대체 리소스와의 공존은
 * {@code RoomPagePreviewIntegrationTest} 가 본다.
 */
class RoomPagePreviewControllerTest {

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="ko">
              <head>
                <!--og:start-->
                <title>Mirboard</title>
                <meta property="og:title" content="미르보드카페 — 공통" />
                <!--og:end-->
              </head>
              <body><div id="root"></div></body>
            </html>
            """;

    private static final String ROOM_ID = "3f2c9a4e-1b7d-4c55-9e0a-0f6b8d2a7c11";
    private static final long HOST_ID = 2L;

    private final RoomService rooms = mock(RoomService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final GameDefinition game = mock(GameDefinition.class);
    private GameRegistry registry;

    @BeforeEach
    void setUp() {
        when(game.id()).thenReturn("ONE_CARD");
        when(game.displayName()).thenReturn("원카드");
        registry = new GameRegistry(List.of(game));
        when(users.findById(HOST_ID)).thenReturn(Optional.of(User.create("alice", "x", Clock.systemUTC())));
    }

    private static Room room(String name, String gameType, int playerCount) {
        List<Long> players = List.of(HOST_ID, 3L, 4L).subList(0, playerCount);
        return new Room(ROOM_ID, name, gameType, HOST_ID, RoomStatus.WAITING, 4, playerCount, players,
                Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private MockMvc mvc(IndexHtmlTemplate template) {
        return MockMvcBuilders
                .standaloneSetup(new RoomPagePreviewController(template, rooms, registry, users))
                .build();
    }

    private static IndexHtmlTemplate templateOf(String html) {
        return new IndexHtmlTemplate(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
    }

    private static IndexHtmlTemplate noTemplate() {
        return new IndexHtmlTemplate(new ClassPathResource("static/no-such-index.html"));
    }

    @Test
    @DisplayName("방이 있으면 그 방의 미리보기 HTML — text/html UTF-8, 공유 캐시 금지(no-cache)")
    void renders_room_preview() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("금요일 한 판", "ONE_CARD", 3));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("<title>원카드 · 금요일 한 판 — 미르보드카페</title>")
                .contains("<meta property=\"og:description\" content=\"alice님이 초대했어요 · 4인 방 · 누르면 바로 입장\" />")
                .contains("<meta property=\"og:url\" content=\"http://localhost/rooms/" + ROOM_ID + "\" />")
                .contains("<div id=\"root\"></div>")
                .doesNotContain("미르보드카페 — 공통");
    }

    /**
     * 카톡 같은 스크래퍼의 Accept 는 알 수 없다. produces 로 협상하면 HTML 이 아닌 Accept 는 406(빈 본문)을 받고 SPA 대체 리소스로도
     * 넘어가지 않았다(사전 리뷰 IP-1 — 전체 컨텍스트 탐침에서 json·plain·image·xhtml 모두 406). 응답 타입은 명시하고 협상하지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain", "application/xhtml+xml", "image/*"})
    @DisplayName("Accept 가 HTML 이 아니어도(스크래퍼마다 다르다) 406 이 아니라 미리보기 HTML")
    void any_accept_gets_preview(String accept) throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID).header("Accept", accept))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:title\" content=\"원카드 · 방\" />");
    }

    @Test
    @DisplayName("현재 인원(3/4)은 미리보기에 없다 — 정원만")
    void excludes_current_player_count() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 3));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("4인 방").doesNotContain("3/4").doesNotContain("3명");
    }

    @Test
    @DisplayName("og:url 은 프록시 뒤 실제 주소 — forwarded 헤더(운영 framework 전략의 필터)를 반영하고 쿼리는 뺀다")
    void og_url_follows_forwarded_headers() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));
        MockMvc forwarded = MockMvcBuilders
                .standaloneSetup(new RoomPagePreviewController(templateOf(TEMPLATE), rooms, registry, users))
                .addFilters(new ForwardedHeaderFilter())
                .build();

        String body = forwarded.perform(get("/rooms/" + ROOM_ID + "?utm=kakao")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "mirboard.fly.dev")
                        .header("X-Forwarded-Port", "443"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:url\" content=\"https://mirboard.fly.dev/rooms/" + ROOM_ID + "\" />");
        // D-138 — 이미지도 프록시 뒤 실제 출처.
        assertThat(body).contains("<meta property=\"og:image\" content=\"https://mirboard.fly.dev/og-image.jpg\" />");
    }

    @Test
    @DisplayName("게임 표시 이름은 GameRegistry 에서 — 모르는 게임이면 게임 ID 그대로")
    void game_name_from_registry() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "NEW_GAME", 1));

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("<meta property=\"og:title\" content=\"NEW_GAME · 방\" />");
    }

    @Test
    @DisplayName("방장 계정을 못 찾으면 초대한 사람 없이 정원만")
    void unknown_host() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));
        when(users.findById(HOST_ID)).thenReturn(Optional.empty());

        String body = mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("content=\"4인 방 · 누르면 바로 입장\"");
    }

    @Test
    @DisplayName("없는 방이면 틀 그대로(공통 미리보기의 SPA) — 클라가 \"방이 없어졌어요\"를 보인다")
    void missing_room_returns_template() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenThrow(new RoomNotFoundException(ROOM_ID));

        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("방 조회가 다른 이유로 실패해도(저장소 장애) 예외가 새지 않고 틀 그대로")
    void lookup_failure_returns_template() throws Exception {
        when(rooms.getRoom(ROOM_ID)).thenThrow(new IllegalStateException("redis down"));

        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("방 ID 모양(영숫자·하이픈 64자 이하)이 아니면 저장소를 보지 않고 틀 그대로")
    void odd_room_id_is_not_looked_up() throws Exception {
        mvc(templateOf(TEMPLATE)).perform(get("/rooms/abc:players"))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
        mvc(templateOf(TEMPLATE)).perform(get("/rooms/" + "a".repeat(65)))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));

        verify(rooms, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("화면 틀이 없으면(개발·테스트 빌드) 기존 리소스 처리와 같게 404 — 방은 보지 않는다")
    void no_template_is_404() throws Exception {
        mvc(noTemplate()).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isNotFound());

        verify(rooms, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("틀에 표식이 없으면 틀 그대로")
    void template_without_markers() throws Exception {
        String plain = "<html><head><title>Mirboard</title></head><body></body></html>";
        when(rooms.getRoom(ROOM_ID)).thenReturn(room("방", "ONE_CARD", 1));

        mvc(templateOf(plain)).perform(get("/rooms/" + ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(plain));
    }
}
