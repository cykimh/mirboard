package com.mirboard.infra.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.springframework.stereotype.Component;

/**
 * D-117 — 레이트리밋 버킷 키로 쓸 클라이언트 IP 해석.
 *
 * <p>운영은 `server.forward-headers-strategy: framework` 라 {@code request.getRemoteAddr()} 가
 * {@code X-Forwarded-For}·{@code Forwarded} 의 <b>맨 왼쪽 값</b>이다 — 클라가 그대로 써 넣을 수
 * 있는 값이라, 매 요청 바꾸면 IP 버킷이 매번 새로 생겼다(D-90 결함). 그래서 레이트리밋은
 * 그 경로를 믿지 않고, 프록시가 <b>덮어쓰는</b> 신뢰 헤더 하나({@code mirboard.ratelimit.
 * client-ip-header}, 운영 기본 {@code Fly-Client-IP})만 본다. 설정이 없으면(로컬·CI)
 * remoteAddr — 프록시가 없는 환경에서 헤더를 믿으면 클라가 키를 고르게 된다.
 *
 * <p>값은 {@link InetAddress#ofLiteral} 로 <b>IP 리터럴만</b> 받는다. {@code getByName} 은
 * 이름이면 DNS 를 조회하므로, 헤더에 넣은 임의 이름으로 서버가 외부 질의를 하게 된다.
 * 리터럴이 아니면 remoteAddr 로 폴백한다.
 *
 * <p>IPv6 는 앞 64비트만 남긴 {@code x:x:x:x::/64} 로 묶는다. 한 가구·회선이 /64 를 통째로
 * 받으므로 주소 하나 단위면 IP 당 한도가 무의미하다(같은 /64 를 나눠 쓰는 사무실은 한도를
 * 공유하는 대가 — D-117).
 */
@Component
public class ClientIpResolver {

    private final RateLimitProperties props;

    public ClientIpResolver(RateLimitProperties props) {
        this.props = props;
    }

    public String resolve(HttpServletRequest request) {
        String header = props.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String fromProxy = normalize(request.getHeader(header));
            if (fromProxy != null) {
                return fromProxy;
            }
        }
        String remote = request.getRemoteAddr();
        String normalized = normalize(remote);
        return normalized != null ? normalized : remote;
    }

    /** IP 리터럴이면 버킷 키 형태로, 아니면 null. DNS 조회 없음. */
    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            return String.format("%x:%x:%x:%x::/64",
                    group(b, 0), group(b, 2), group(b, 4), group(b, 6));
        }
        return address.getHostAddress();
    }

    private static int group(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }
}
