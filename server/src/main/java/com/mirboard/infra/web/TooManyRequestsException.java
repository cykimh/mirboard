package com.mirboard.infra.web;

/**
 * D-84 — 인증 엔드포인트 IP 레이트리밋 초과. {@link GlobalExceptionHandler} 가 429 로 매핑.
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException() {
        super("Too many requests");
    }
}
