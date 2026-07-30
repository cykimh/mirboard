package com.mirboard.infra.rest.chat;

import com.mirboard.domain.admin.ChatLogStore;
import com.mirboard.domain.admin.ChatReport;
import com.mirboard.domain.admin.ChatReportService;
import com.mirboard.domain.lobby.auth.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * D-93 — 채팅 신고 접수(일반 사용자). 조회는 어드민 전용이라
 * {@code /api/admin/chat-reports}(AdminController) 에 따로 있다.
 *
 * <p>요청은 <b>eventId 만</b> 받는다 — 본문을 받으면 "상대가 이런 말을 했다"를 위조할 수
 * 있다. 원문·작성자는 서버가 {@link ChatLogStore} 보관분에서 확정한다.
 */
@RestController
@RequestMapping("/api/chat/reports")
public class ChatReportController {

    private final ChatReportService reports;

    public ChatReportController(ChatReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse report(@AuthenticationPrincipal AuthPrincipal principal,
                                 @RequestBody @Valid ReportRequest req) {
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        String scope = ChatLogStore.SCOPE_ROOM.equalsIgnoreCase(req.scope())
                ? ChatLogStore.SCOPE_ROOM
                : ChatLogStore.SCOPE_LOBBY;
        ChatReport saved = reports.report(scope, req.roomId(), req.eventId(), principal.userId());
        return new ReportResponse(saved.getId(), saved.getEventId());
    }

    /**
     * @param eventId 신고 대상 메시지의 envelope eventId (클라 `roomChatStore` 가 보관 중)
     * @param scope   "ROOM" | "LOBBY" (대소문자 무시, 그 외는 LOBBY 로 취급)
     * @param roomId  scope=ROOM 일 때 필수
     */
    public record ReportRequest(
            @NotBlank @Size(max = 36) String eventId,
            @NotBlank @Size(max = 8) String scope,
            @Size(max = 36) String roomId) {
    }

    public record ReportResponse(long reportId, String eventId) {
    }
}
