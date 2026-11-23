package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Documentation-reserved addresses only (RFC 5737, RFC 3849). */
class ClientIpResolverTests {

    private final ClientIpResolver noProxies = new ClientIpResolver(List.of());
    private final ClientIpResolver behindProxies =
            new ClientIpResolver(List.of("10.0.0.0/8", " 192.0.2.10 ", "2001:db8:ffff::/48"));

    @Test
    void withoutTrustedProxiesForwardingHeadersAreIgnored() {
        MockHttpServletRequest request = request("203.0.113.1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");
        request.addHeader(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.2");

        assertThat(noProxies.resolve(request).getHostAddress()).isEqualTo("203.0.113.1");
    }

    @Test
    void fromAnUntrustedAddressForwardingHeadersAreIgnored() {
        MockHttpServletRequest request = request("203.0.113.1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");
        request.addHeader(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("203.0.113.1");
    }

    @Test
    void fromATrustedProxyCloudflareHeaderWins() {
        MockHttpServletRequest request = request("192.0.2.10");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");
        request.addHeader(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("198.51.100.2");
    }

    @Test
    void forwardedForIsWalkedFromTheRightSkippingTrustedProxies() {
        MockHttpServletRequest request = request("10.0.0.1");
        // The leftmost value was chosen by the client: only the rightmost untrusted hop is reliable.
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.66, 203.0.113.9, 10.0.0.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void severalForwardedForHeadersAreReadInOrder() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.66");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "203.0.113.9, 10.0.0.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void whenEveryHopIsTrustedTheLeftmostIsTheClient() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "10.0.0.3, 10.0.0.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("10.0.0.3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "example.test", "198.51.100.1:443", "", "999.1.1.1"})
    void aMalformedHopStopsTheWalk(String malformed) {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.66, " + malformed + ", 10.0.0.2");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("10.0.0.2");
    }

    @Test
    void aMalformedCloudflareHeaderFallsBackToForwardedFor() {
        MockHttpServletRequest request = request("192.0.2.10");
        request.addHeader(ClientIpResolver.CF_CONNECTING_IP, "not-an-ip");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("198.51.100.1");
    }

    @Test
    void supportsIpv6RangesAndAddresses() {
        MockHttpServletRequest request = request("2001:db8:ffff::1");
        request.addHeader(ClientIpResolver.X_FORWARDED_FOR, "2001:db8:1::42");

        assertThat(behindProxies.resolve(request).getHostAddress()).isEqualTo("2001:db8:1:0:0:0:0:42");
    }

    @Test
    void rangesMatchOnlyTheirPrefix() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("192.0.2.128/25"));
        MockHttpServletRequest inside = request("192.0.2.200");
        inside.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");
        MockHttpServletRequest outside = request("192.0.2.100");
        outside.addHeader(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.1");

        assertThat(resolver.resolve(inside).getHostAddress()).isEqualTo("198.51.100.1");
        assertThat(resolver.resolve(outside).getHostAddress()).isEqualTo("192.0.2.100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy.example.test", "10.0.0.0/33", "10.0.0.0/x", "2001:db8::/129", ""})
    void anInvalidTrustedProxyIsRejectedAtStartup(String invalid) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientIpResolver(List.of(invalid)))
                .withMessageContaining("DENTASOFT_TRUSTED_PROXIES");
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
