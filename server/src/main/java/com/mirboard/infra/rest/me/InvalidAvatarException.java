package com.mirboard.infra.rest.me;

/**
 * 업로드 아바타가 부적합(빈 파일·미지원 형식·과대 용량/해상도·변환 실패)할 때.
 * {@code GlobalExceptionHandler} 가 400 {@code ApiErrorEnvelope} 로 매핑한다.
 */
public class InvalidAvatarException extends RuntimeException {
    public InvalidAvatarException(String message) {
        super(message);
    }
}
