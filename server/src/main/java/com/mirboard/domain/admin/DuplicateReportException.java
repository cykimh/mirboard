package com.mirboard.domain.admin;

/** D-93 — 같은 사용자가 같은 메시지를 재신고. 409 DUPLICATE_REPORT 로 매핑(남용 방지). */
public class DuplicateReportException extends RuntimeException {
    public DuplicateReportException(String eventId) {
        super("Already reported: " + eventId);
    }
}
