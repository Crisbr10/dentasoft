package es.crisbr.dentasoft.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Finds the IP address of the client behind the proxies listed in {@code DENTASOFT_TRUSTED_PROXIES}.
 *
 * <p>Forwarding headers are only read when the connection comes from a trusted proxy; otherwise anyone could
 * choose their own IP by sending them. Then {@code CF-Connecting-IP} wins if present, and {@code X-Forwarded-For}
 * is walked from right to left, skipping trusted proxies: the first untrusted hop is the client. Only list proxies
 * that overwrite these headers, never ones that pass a client's value through.
 */
class ClientIpResolver {

    static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<IpRange> trustedProxies;

    ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies =
                trustedProxies.stream().map(String::strip).map(IpRange::parse).toList();
    }

    InetAddress resolve(HttpServletRequest request) {
        InetAddress remote = IpRange.literal(request.getRemoteAddr());
        if (remote == null) {
            throw new IllegalStateException("The connection has no IP address");
        }
        if (!isTrusted(remote)) {
            return remote;
        }
        InetAddress cloudflare = IpRange.literal(strip(request.getHeader(CF_CONNECTING_IP)));
        if (cloudflare != null) {
            return cloudflare;
        }
        InetAddress client = remote;
        for (String hop : forwardedHopsRightToLeft(request)) {
            InetAddress address = IpRange.literal(hop);
            if (address == null) {
                // A malformed hop cannot be trusted, and neither can anything to its left.
                return client;
            }
            client = address;
            if (!isTrusted(address)) {
                return address;
            }
        }
        return client;
    }

    private boolean isTrusted(InetAddress address) {
        for (IpRange range : trustedProxies) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> forwardedHopsRightToLeft(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(X_FORWARDED_FOR))) {
            for (String hop : header.split(",")) {
                hops.add(hop.strip());
            }
        }
        Collections.reverse(hops);
        return hops;
    }

    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }

    /** One IP address or CIDR range ({@code 10.0.0.0/8}, {@code 2001:db8::/32}). */
    record IpRange(byte[] network, int prefixLength) {

        static IpRange parse(String value) {
            int slash = value.indexOf('/');
            InetAddress address = literal(slash < 0 ? value : value.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException(
                        "Invalid trusted proxy in DENTASOFT_TRUSTED_PROXIES: '" + value + "'");
            }
            int maxPrefix = address.getAddress().length * 8;
            int prefix = maxPrefix;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    prefix = -1;
                }
                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException(
                            "Invalid prefix length in DENTASOFT_TRUSTED_PROXIES: '" + value + "'");
                }
            }
            return new IpRange(address.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        /** Parses an IP literal without ever doing a DNS lookup; {@code null} if it is not one. */
        static @Nullable InetAddress literal(@Nullable String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            try {
                return InetAddress.ofLiteral(value);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
