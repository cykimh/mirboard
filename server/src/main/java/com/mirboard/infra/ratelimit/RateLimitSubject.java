package com.mirboard.infra.ratelimit;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import java.security.Principal;

/**
 * D-90 — 레이트리밋 카운터의 키가 되는 "주체" 식별자.
 *
 * <p>인증된 요청은 {@code u:{userId}}, 아니면 {@code ip:{addr}} 를 쓴다. 같은 NAT
 * (집·카페 Wi-Fi, 모바일 캐리어) 뒤의 여러 사용자가 서로의 할당량을 깎지 않게 하려는
 * 것 — 지인 대상 서비스라 오탐 비용이 회피 비용보다 크다. 계정 다중생성으로 우회하는
 * 경로는 가입/로그인의 IP 버킷(`auth`)이 이미 막는다.
 */
public final class RateLimitSubject {

    private RateLimitSubject() {
    }

    /** 인증 주체가 있으면 userId 기준, 없으면 IP 기준. */
    public static String of(Principal principal, String clientIp) {
        if (principal instanceof AuthPrincipal auth) {
            return ofUser(auth.userId());
        }
        return ofIp(clientIp);
    }

    public static String ofUser(long userId) {
        return "u:" + userId;
    }

    /** IP 가 비어 있으면(테스트/유닉스 소켓 등) 단일 버킷으로 묶어 무제한이 되는 것을 막는다. */
    public static String ofIp(String clientIp) {
        return "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }
}
