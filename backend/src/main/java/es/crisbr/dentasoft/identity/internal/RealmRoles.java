package es.crisbr.dentasoft.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The Keycloak realm roles of the token ({@code realm_access.roles}) that Dentasoft knows, as {@code ROLE_} authorities.
 * Only {@code PLATFORM_ADMIN} is a realm role: the roles inside a clinic come from its membership (M02-T03, M02-T04),
 * never from the token. Anything else in the claim ({@code offline_access}, the realm's default roles), the scopes and
 * the client roles are ignored, so a role added in Keycloak grants nothing until the code knows it.
 */
final class RealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String CLAIM = "realm_access";
    static final Set<String> KNOWN = Set.of("PLATFORM_ADMIN");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(CLAIM) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(KNOWN::contains)
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
