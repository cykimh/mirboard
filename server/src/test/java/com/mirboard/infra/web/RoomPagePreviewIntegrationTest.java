package com.mirboard.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-133 — 방 미리보기를 실제 필터 체인·저장소 위에서. 카톡 스크래퍼는 토큰 없이 긁으므로 {@code /rooms/{roomId}} 는 인증 없이
 * 열려야 한다 — {@code SecurityConfig} 는 {@code /api} 밖을 기본 허용하므로 고치지 않는다(이 테스트가 그 사실을 고정한다).
 * 같은 이름의 REST({@code /api/rooms/{id}})·WebSocket({@code /ws})·다른 경로의 리소스 처리는 그대로여야 한다.
 *
 * <p>테스트 클래스패스에는 빌드된 {@code static/index.html} 이 없어 화면 틀은 목으로 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "mirboard.jwt.secret=room-preview-test-secret-must-be-32-bytes-or-more"
})
class RoomPagePreviewIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    IndexHtmlTemplate template;

    @BeforeEach
    void serveTemplate() {
        when(template.html()).thenReturn(Optional.of(TEMPLATE));
    }

    @Test
    @DisplayName("토큰 없이 방 주소를 열면 그 방의 미리보기 HTML — 방 이름은 이스케이프, 방장·정원 포함")
    void anonymous_preview_of_real_room() throws Exception {
        String token = registerAndLogin("preview_host", "validpass1");
        JsonNode room = createRoom(token, "<b>금요일</b> 한 판", "ONE_CARD");
        String roomId = room.get("roomId").asText();

        String body = mockMvc.perform(get("/rooms/" + roomId).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("<meta property=\"og:title\" content=\"원카드 · &lt;b&gt;금요일&lt;/b&gt; 한 판\" />")
                .contains("preview_host님이 초대했어요 · " + room.get("capacity").asInt() + "인 방 · 누르면 바로 입장")
                .contains("<meta property=\"og:url\" content=\"http://localhost/rooms/" + roomId + "\" />")
                .doesNotContain("<b>금요일");
    }

    @Test
    @DisplayName("없는 방이면 틀 그대로 200 — 공통 미리보기의 SPA")
    void missing_room_serves_template() throws Exception {
        mockMvc.perform(get("/rooms/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(content().string(TEMPLATE));
    }

    @Test
    @DisplayName("화면 틀이 없으면 404")
    void no_template_is_404() throws Exception {
        when(template.html()).thenReturn(Optional.empty());

        mockMvc.perform(get("/rooms/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("같은 방의 REST(/api/rooms/{id}/…)는 그대로 인증이 필요하다 — 미리보기 매핑이 가로채지 않는다")
    void api_rooms_still_requires_auth() throws Exception {
        String token = registerAndLogin("preview_api", "validpass1");
        String roomId = createRoom(token, "방", "ONE_CARD").get("roomId").asText();

        mockMvc.perform(post("/api/rooms/" + roomId + "/join-or-reconnect"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미리보기는 /rooms/{id} 한 단계만 — /rooms·/rooms/{id}/더 는 기존 리소스 처리(여기선 틀이 없어 404)")
    void other_room_paths_fall_through() throws Exception {
        mockMvc.perform(get("/rooms")).andExpect(status().isNotFound());
        mockMvc.perform(get("/rooms/abc/extra")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("WebSocket(SockJS) 정보 엔드포인트는 그대로 열린다")
    void websocket_endpoint_untouched() throws Exception {
        String body = mockMvc.perform(get("/ws/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"websocket\"");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", username, "password", password));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }

    private JsonNode createRoom(String token, String name, String gameType) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "gameType", gameType));
        String created = mockMvc.perform(post("/api/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(created);
    }
}
