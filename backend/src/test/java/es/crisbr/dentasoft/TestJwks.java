package es.crisbr.dentasoft;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * The signing key of the tests, generated when the tests start, and a local HTTP server that publishes its public
 * half as Keycloak would ({@link #uri()}). The application under test checks tokens against it exactly as against
 * Keycloak, so no real Keycloak is needed. Started once for the whole test run, on a free port of the loopback
 * interface.
 */
public final class TestJwks {

    static final String PATH = "/realms/dentasoft/protocol/openid-connect/certs";

    private static final RSAKey KEY = generate("test-signing-key");
    private static final HttpServer SERVER = start();

    private TestJwks() {}

    /** The key the tests sign their tokens with; the server publishes its public half. */
    public static RSAKey key() {
        return KEY;
    }

    /** A new key of the same size the server does not publish. */
    public static RSAKey otherKey(String keyId) {
        return generate(keyId);
    }

    public static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + PATH;
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext(PATH, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
