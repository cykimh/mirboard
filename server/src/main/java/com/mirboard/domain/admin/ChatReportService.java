package com.mirboard.domain.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-93 — 채팅 신고 접수/조회. 컨트롤러는 이 서비스를 호출만 한다(규칙#4).
 *
 * <p>신고자는 {@code eventId} 로 "어느 메시지인지"만 지목하고, 원문·작성자는 서버가
 * {@link ChatLogStore} 보관분에서 확정한다 — 클라가 본문을 제출하면 무고가 가능하다.
 */
@Service
public class ChatReportService {

    private static final Logger log = LoggerFactory.getLogger(ChatReportService.class);

    private final ChatLogStore chatLog;
    private final ChatReportRepository reports;
    private final Clock clock;

    public ChatReportService(ChatLogStore chatLog, ChatReportRepository reports, Clock clock) {
        this.chatLog = chatLog;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * 신고 접수.
     *
     * @throws ChatMessageNotFoundException 링버퍼에 없음(TTL 만료·밀려남·잘못된 eventId)
     * @throws SelfReportException          자기 메시지 신고
     * @throws DuplicateReportException     같은 사람이 같은 메시지를 재신고
     */
    @Transactional
    public ChatReport report(String scope, String roomId, String eventId, long reporterUserId) {
        ChatLogStore.Entry entry = chatLog.find(scope, roomId, eventId)
                .orElseThrow(() -> new ChatMessageNotFoundException(eventId));

        if (entry.userId() == reporterUserId) {
            throw new SelfReportException();
        }
        // UNIQUE 제약이 최종 방어선이지만, 먼저 조회해 흔한 경우를 깔끔한 예외로 만든다.
        if (reports.existsByEventIdAndReporterUserId(eventId, reporterUserId)) {
            throw new DuplicateReportException(eventId);
        }

        ChatReport saved;
        try {
            saved = reports.save(new ChatReport(
                    eventId,
                    scope,
                    ChatLogStore.SCOPE_ROOM.equals(scope) ? roomId : null,
                    entry.userId(),
                    reporterUserId,
                    entry.message(),
                    Instant.ofEpochMilli(entry.ts()),
                    Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // 동시 신고 레이스 — UNIQUE 가 잡았다.
            throw new DuplicateReportException(eventId);
        }

        log.info("채팅 신고 접수: reportId={} scope={} reported={} reporter={}",
                saved.getId(), scope, entry.userId(), reporterUserId);
        return saved;
    }

    /** 어드민 목록 — 최신순. */
    @Transactional(readOnly = true)
    public List<ChatReport> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return reports.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped));
    }

    /** 특정 유저가 받은 누적 신고 수 — 정지 판단 근거. */
    @Transactional(readOnly = true)
    public long countAgainst(long userId) {
        return reports.countByReportedUserId(userId);
    }
}
