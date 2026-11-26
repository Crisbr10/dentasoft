package es.crisbr.dentasoft;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Access tokens as Keycloak issues them for the {@code dentasoft} realm, signed with the key of {@link TestJwks}. By
 * default a token is valid: issuer {@link #ISSUER}, audience {@code dentasoft-api}, five minutes of life and a random
 * user. Each method changes one thing, so a test can build exactly the token it rejects.
 */
public final class TestTokens {

    public static final String ISSUER = WithTestEnvironment.AUTH + "/realms/dentasoft";
    public static final String AUDIENCE = "dentasoft-api";

    private final JWTClaimsSet.Builder claims;
    private String keyId = TestJwks.key().getKeyID();

    private TestTokens(String subject) {
        Instant now = Instant.now();
        this.claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE, "account"))
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("azp", "dentasoft-web")
                .claim("typ", "Bearer");
    }

    /** A valid token of a random user without realm roles. */
    public static TestTokens token() {
        return token(UUID.randomUUID());
    }

    public static TestTokens token(UUID subject) {
        return new TestTokens(subject.toString());
    }

    /** A valid token of a platform administrator. */
    public static TestTokens platformAdmin(UUID subject) {
        return token(subject).realmRoles("PLATFORM_ADMIN");
    }

    /** {@code Bearer <a valid token of a random user>}, for the requests that only need someone signed in. */
    public static String anyUser() {
        return token().bearer();
    }

    public TestTokens subject(@Nullable String subject) {
        claims.subject(subject);
        return this;
    }

    public TestTokens issuer(String issuer) {
        claims.issuer(issuer);
        return this;
    }

    public TestTokens audience(String... audience) {
        claims.audience(List.of(audience));
        return this;
    }

    /** Also moves the issue time to five minutes before, as Keycloak issues them: a token never expires before it is issued. */
    public TestTokens expiresAt(@Nullable Instant expiresAt) {
        claims.expirationTime(expiresAt == null ? null : Date.from(expiresAt));
        if (expiresAt != null) {
            claims.issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))));
        }
        return this;
    }

    public TestTokens realmRoles(String... roles) {
        claims.claim("realm_access", Map.of("roles", List.of(roles)));
        return this;
    }

    public TestTokens claim(String name, @Nullable Object value) {
        claims.claim(name, value);
        return this;
    }

    public TestTokens keyId(String keyId) {
        this.keyId = keyId;
        return this;
    }

    /** Signed with the key the application trusts. */
    public String sign() {
        return signWith(TestJwks.key());
    }

    /** Signed with {@code key}, under the key ID set with {@link #keyId} (the trusted key's by default). */
    public String signWith(RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(header(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Not signed at all: {@code alg: none} and an empty signature. */
    public String unsigned() {
        return new PlainJWT(claims.build()).serialize();
    }

    /**
     * HS256 with the trusted public key as the HMAC secret: the attack on decoders that pick the algorithm from the
     * token and verify an HMAC with whatever key they hold.
     */
    public String hs256WithPublicKey() {
        try {
            SignedJWT jwt = new SignedJWT(header(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(TestJwks.key().toRSAPublicKey().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code Bearer <signed token>}, for the {@code Authorization} header. */
    public String bearer() {
        return "Bearer " + sign();
    }

    private JWSHeader header(JWSAlgorithm algorithm) {
        return new JWSHeader.Builder(algorithm)
                .type(JOSEObjectType.JWT)
                .keyID(keyId)
                .build();
    }
}
