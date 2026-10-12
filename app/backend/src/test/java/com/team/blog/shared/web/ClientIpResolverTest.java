package com.team.blog.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 요청 제한의 기준이 되는 사용자 IP 판정. 위조한 X-Forwarded-For로 우회하거나 DNS를 일으키지 못해야 한다. */
class ClientIpResolverTest {
    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "fd00::/8"));

    private static MockHttpServletRequest request(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void 신뢰_밖에서_온_요청은_헤더를_무시하고_접속_주소를_쓴다() {
        assertThat(resolver.resolve(request("203.0.113.9", "1.1.1.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void 로드_밸런서를_거친_요청은_오른쪽부터_첫_신뢰_밖_주소를_쓴다() {
        // 사용자가 맨 앞에 가짜 주소를 넣어도 로드 밸런서가 덧붙인 실제 주소가 쓰인다
        assertThat(resolver.resolve(request("10.1.2.3", "6.6.6.6, 198.51.100.7, 10.0.0.5"))).isEqualTo("198.51.100.7");
    }

    @Test
    void 헤더가_없거나_모두_신뢰_대역이면_접속_주소를_쓴다() {
        assertThat(resolver.resolve(request("10.1.2.3", null))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", " , 10.9.9.9"))).isEqualTo("10.1.2.3");
    }

    @Test
    void IPv6_대역도_가린다() {
        assertThat(resolver.resolve(request("fd12::1", "2001:db8::1, fd00::2"))).isEqualTo("2001:db8::1");
        assertThat(resolver.resolve(request("2001:db8::9", "1.1.1.1"))).isEqualTo("2001:db8::9");
    }

    @Test
    void 대역_경계를_정확히_가른다() {
        ClientIpResolver narrow = new ClientIpResolver(List.of("192.168.4.0/22"));
        assertThat(narrow.resolve(request("192.168.7.255", "8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(narrow.resolve(request("192.168.8.0", "8.8.8.8"))).isEqualTo("192.168.8.0");
    }

    @Test
    void 숫자_주소가_아니면_DNS를_묻지_않고_신뢰하지_않는다() {
        assertThat(ClientIpResolver.Cidr.toBytes("999.1.1.1")).isNull();
        assertThat(ClientIpResolver.Cidr.toBytes("1.2.3.256")).isNull();
        assertThat(ClientIpResolver.Cidr.toBytes("example.com")).isNull();
        assertThat(ClientIpResolver.Cidr.toBytes("255.255.255.255")).hasSize(4);
        // 신뢰 대역 안에서 숫자가 아닌 홉이 오면 그 값을 그대로 신뢰 밖 주소로 본다
        assertThat(resolver.resolve(request("10.1.2.3", "unknown"))).isEqualTo("unknown");
    }

    @Test
    void 잘못된_신뢰_대역_설정은_시작할_때_막는다() {
        List<String> badPrefix = List.of("10.0.0.0/33");
        List<String> notIp = List.of("not-an-ip");
        assertThatThrownBy(() -> new ClientIpResolver(badPrefix)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(notIp)).isInstanceOf(IllegalArgumentException.class);
    }
}
