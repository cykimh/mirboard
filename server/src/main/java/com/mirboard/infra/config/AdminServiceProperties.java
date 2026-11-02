package com.mirboard.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-170 — 허브 서버가 {@code /api/admin/**} 를 서버끼리 호출할 때 쓰는 서비스 토큰.
 *
 * <p>{@code serviceToken} 이 비었거나 {@code serviceUserId} ≤ 0 이면 분기 전체가 꺼진다
 * (코드 기본값 = 꺼짐). 권한은 새로 만들지 않는다 — {@code serviceUserId} 가 {@code admin_roles}
 * 에 있어야 어드민 API 가 열린다. 값은 Fly secrets 로만 주입(코드·문서에 적지 않는다).
 */
@ConfigurationProperties("mirboard.admin")
public record AdminServiceProperties(String serviceToken, long serviceUserId) {

    public AdminServiceProperties {
        serviceToken = serviceToken == null ? "" : serviceToken;
    }

    /** 토큰이 설정되고 서비스 사용자 id 가 양수일 때만 켜진다. */
    public boolean enabled() {
        return !serviceToken.isBlank() && serviceUserId > 0;
    }

    @Override
    public String toString() {
        // 토큰이 로그·예외 메시지에 새지 않게 값은 찍지 않는다.
        return "AdminServiceProperties[enabled=" + enabled() + ", serviceUserId=" + serviceUserId + "]";
    }
}
