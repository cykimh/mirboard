package com.mirboard.domain.admin;

/** D-86 — 어드민 권한이 없는 사용자가 /api/admin/** 접근 시. 403 NOT_ADMIN 으로 매핑. */
public class NotAdminException extends RuntimeException {
    public NotAdminException() {
        super("Admin privilege required");
    }
}
