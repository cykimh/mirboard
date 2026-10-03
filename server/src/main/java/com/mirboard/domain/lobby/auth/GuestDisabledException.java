package com.mirboard.domain.lobby.auth;

/** D-117 — 킬스위치({@code mirboard.guest.enabled=false})로 게스트 생성이 꺼져 있다. 403 GUEST_DISABLED. */
public class GuestDisabledException extends RuntimeException {
    public GuestDisabledException() {
        super("Guest accounts are disabled");
    }
}
