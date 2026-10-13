package com.mirboard.infra.web;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.web.RoomPreviewRenderer.RoomPreview;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-133 — 방 주소({@code /rooms/{roomId}})의 HTML 에 그 방의 미리보기(Open Graph)를 넣는다. 공유 링크가 방 주소 그대로라 카톡
 * 같은 스크래퍼(자바스크립트를 실행하지 않는다)가 이 응답의 메타로 말풍선을 그리고, 브라우저에게는 같은 SPA 다(클라 라우터가 방 화면을
 * 그린다). 컨트롤러 매핑이 SPA 대체 리소스 핸들러({@code StaticSpaConfig})보다 먼저 잡는다.
 *
 * <ul>
 *   <li>{@code /api} 밖이라 인증이 없다 — {@code SecurityConfig} 는 {@code /api} 밖을 기본 허용한다(스크래퍼는 토큰이 없다). 레이트리밋
 *       ({@code /api/**} 전용)도 걸지 않는다 — 방 하나당 저장소 읽기 한 번이고 방 ID 는 추측할 수 없는 UUID 다.</li>
 *   <li>방이 없거나 조회가 실패하면 틀 그대로(사이트 공통 미리보기의 SPA — 클라가 "방이 없어졌어요"를 보인다). 예외를 HTML 응답
 *       밖으로 내보내지 않는다. 틀이 없으면(클라 번들 미동봉 빌드) 기존 리소스 처리와 같게 404.</li>
 *   <li>{@code Cache-Control: no-cache} — 방마다 내용이 달라 공유 캐시 금지(브라우저는 재검증). Accept 와 무관하게 늘
 *       {@code text/html;charset=UTF-8} 이다(협상하지 않는다).</li>
 *   <li>게임 표시 이름은 {@link GameRegistry} 의 정의에서 얻는다 — 인프라에 게임 이름 분기를 두지 않는다. 새로 드러나는 정보는 없다
 *       (방 이름·방장·게임·정원은 로비 목록에 이미 공개).</li>
 * </ul>
 */
@RestController
public class RoomPagePreviewController {

    private static final Logger log = LoggerFactory.getLogger(RoomPagePreviewController.class);

    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    /** 방 ID 는 UUID. 그 밖의 모양은 저장소를 보지 않는다(키 이름에 섞여 다른 자료형을 건드리지 않게). */
    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final IndexHtmlTemplate template;
    private final RoomService rooms;
    private final GameRegistry games;
    private final UserRepository users;

    public RoomPagePreviewController(IndexHtmlTemplate template, RoomService rooms, GameRegistry games,
                                     UserRepository users) {
        this.template = template;
        this.rooms = rooms;
        this.games = games;
        this.users = users;
    }

    /**
     * produces 를 걸지 않는다 — 걸면 Accept 가 HTML 이 아닌 스크래퍼(카톡 등 — 값을 알 수 없다)가 406(빈 본문)을 받고 SPA 대체로도
     * 넘어가지 않는다. Content-Type 은 응답에 명시하고 협상하지 않는다.
     */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<String> page(@PathVariable String roomId, HttpServletRequest request) {
        Optional<String> html = template.html();
        if (html.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String body = preview(roomId, request)
                .map(room -> RoomPreviewRenderer.render(html.get(), room))
                .orElse(html.get());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(HTML_UTF8)
                .body(body);
    }

    private Optional<RoomPreview> preview(String roomId, HttpServletRequest request) {
        if (!ROOM_ID.matcher(roomId).matches()) {
            return Optional.empty();
        }
        try {
            Room room = rooms.getRoom(roomId);
            String gameName = games.find(room.gameType())
                    .map(GameDefinition::displayName)
                    .orElse(room.gameType());
            String hostName = users.findById(room.hostId())
                    .map(User::getUsername)
                    .orElse(null);
            return Optional.of(new RoomPreview(gameName, room.name(), hostName, room.capacity(),
                    request.getRequestURL().toString()));
        } catch (RoomNotFoundException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("방 미리보기 실패 — 공통 미리보기로: roomId={}", roomId, e);
            return Optional.empty();
        }
    }
}
