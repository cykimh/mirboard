package com.mirboard.domain.admin;

/** D-93 — 자기 메시지 신고. 400 SELF_REPORT 로 매핑. */
public class SelfReportException extends RuntimeException {
    public SelfReportException() {
        super("Cannot report your own message");
    }
}
