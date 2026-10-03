package com.mirboard.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * D-117 — 레이트리밋용 클라 IP 해석. 지키려는 것은 둘이다. (1) 클라가 마음대로 넣는
 * `X-Forwarded-For`·`Forwarded` 로 IP 버킷을 갈아 끼울 수 없다 — 운영은 프록시가 덮어쓰는
 * 신뢰 헤더(`Fly-Client-IP`) 하나만 본다. (2) IPv6 는 한 가구가 /64 를 통째로 받으므로
 * 주소 하나가 아니라 /64 단위로 묶어야 IP 당 한도가 의미가 있다.
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(String trustedHeader) {
        RateLimitProperties props = new RateLimitProperties();
        props.setClientIpHeader(trustedHeader);
        return new ClientIpResolver(props);
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    void trusted_header_wins_and_forwarded_headers_are_ignored() {
        MockHttpServletRequest req = request("10.9.9.9");
        req.addHeader("Fly-Client-IP", "203.0.113.7");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        req.addHeader("Forwarded", "for=5.6.7.8");

        assertThat(resolver("Fly-Client-IP").resolve(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void falls_back_to_remote_addr_when_the_trusted_header_is_missing() {
        MockHttpServletRequest req = request("10.9.9.9");
        req.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolver("Fly-Client-IP").resolve(req)).isEqualTo("10.9.9.9");
    }

    @Test
    void unconfigured_header_is_never_trusted() {
        // 로컬·CI(설정 없음)에서는 같은 이름의 헤더를 보내도 무시해야 한다 — 프록시가 없는
        // 환경에서 헤더를 믿으면 클라가 키를 마음대로 고른다.
        MockHttpServletRequest req = request("10.9.9.9");
        req.addHeader("Fly-Client-IP", "203.0.113.7");

        assertThat(resolver(null).resolve(req)).isEqualTo("10.9.9.9");
        assertThat(resolver("  ").resolve(req)).isEqualTo("10.9.9.9");
    }

    @Test
    void ipv6_addresses_in_the_same_slash_64_share_one_key() {
        MockHttpServletRequest a = request("10.9.9.9");
        a.addHeader("Fly-Client-IP", "2001:db8:1:2:3:4:5:6");
        MockHttpServletRequest b = request("10.9.9.9");
        b.addHeader("Fly-Client-IP", "2001:db8:1:2:ffff::1");
        MockHttpServletRequest other = request("10.9.9.9");
        other.addHeader("Fly-Client-IP", "2001:db8:1:3::1");

        ClientIpResolver resolver = resolver("Fly-Client-IP");
        assertThat(resolver.resolve(a)).isEqualTo("2001:db8:1:2::/64");
        assertThat(resolver.resolve(b)).isEqualTo(resolver.resolve(a));
        assertThat(resolver.resolve(other)).isNotEqualTo(resolver.resolve(a));
    }

    @Test
    void ipv6_remote_addr_is_normalized_too() {
        assertThat(resolver(null).resolve(request("2001:db8:aa:bb:1:2:3:4")))
                .isEqualTo("2001:db8:aa:bb::/64");
    }

    @Test
    void non_literal_header_value_falls_back_without_a_dns_lookup() {
        // InetAddress.getByName 이었다면 여기서 DNS 조회가 나간다 — 공격자가 정한 이름으로
        // 서버가 외부 질의를 하게 된다. 리터럴만 받고 나머지는 remoteAddr 로.
        MockHttpServletRequest req = request("10.9.9.9");
        req.addHeader("Fly-Client-IP", "evil.example.com");

        assertThat(resolver("Fly-Client-IP").resolve(req)).isEqualTo("10.9.9.9");
    }

    @Test
    void unparseable_remote_addr_is_kept_as_is() {
        // 유닉스 소켓 등 — 버킷 키로는 여전히 쓸 수 있다(RateLimitSubject 가 공백만 묶는다).
        assertThat(resolver(null).resolve(request("unix-socket"))).isEqualTo("unix-socket");
    }
}
