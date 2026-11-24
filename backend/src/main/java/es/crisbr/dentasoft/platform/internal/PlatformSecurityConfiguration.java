package es.crisbr.dentasoft.platform.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The platform API is closed to every request until authentication exists (M02-T02): this chain, checked before the
 * application's, denies everything under {@link #PATHS}, whoever asks. The services behind it also require {@code
 * PLATFORM_ADMIN} ({@link ClinicAdministration}), so opening the chain will not open them.
 *
 * <p>M02-T02 replaces {@code denyAll()} with an authenticated request that has the {@code PLATFORM_ADMIN} role.
 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    static final String PATHS = "/api/v1/platform/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(PATHS)
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }
}
