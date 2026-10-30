package com.mirboard.domain.lobby.room;

/** D-161 — 대기실 설정 값이 범위 밖이다(턴 제한·목표 점수·버튼 크기). 판돈은 {@link InvalidStakeException}. */
public final class InvalidRoomSettingException extends RuntimeException {

    private final String setting;

    public InvalidRoomSettingException(String setting, Object value) {
        super("Invalid room setting " + setting + ": " + value);
        this.setting = setting;
    }

    public String setting() {
        return setting;
    }
}
