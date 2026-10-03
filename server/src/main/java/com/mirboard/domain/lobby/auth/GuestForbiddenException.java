package com.mirboard.domain.lobby.auth;

/**
 * D-117 — 게스트에게 금지된 행위(비밀번호 변경·아바타 업로드/삭제). 403 GUEST_FORBIDDEN.
 * 일회용 익명 신원이라 비밀번호가 없고, 이미지 업로드 표면을 열어 둘 이유가 없다.
 */
public class GuestForbiddenException extends RuntimeException {
    public GuestForbiddenException() {
        super("Not available for guest accounts");
    }
}
