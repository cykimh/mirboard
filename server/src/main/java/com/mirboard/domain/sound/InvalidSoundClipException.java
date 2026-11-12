package com.mirboard.domain.sound;

/**
 * D-265 — 음성 클립 요청이 부적합할 때(모르는 키·빈 파일·너무 큼·지원하지 않는 형식).
 * {@code GlobalExceptionHandler} 가 400 으로 매핑하고 {@link #code()} 를 에러 코드로 쓴다.
 */
public class InvalidSoundClipException extends RuntimeException {

    private final String code;

    public InvalidSoundClipException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
