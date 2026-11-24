package es.crisbr.dentasoft.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security until authentication exists (M02-T02, which replaces this chain with the JWT Resource Server).
 *
 * <ul>
 *   <li>Method security is on: services declare who may call them with {@code @PreAuthorize}, and deny everyone
 *       while nobody is authenticated.
 *   <li>The application's chain changes nothing yet: requests are let through as before Spring Security was added.
 *       Paths that must stay closed get a chain of their own, ordered before this one (the platform API).
 *   <li>Stateless, without CSRF, login form, HTTP Basic or a request cache. Security headers and CORS stay in {@link
 *       SecurityHeadersFilter} and {@link WebConfiguration}, so Spring Security does not write them a second time.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
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

    /**
     * No username and password users: identities come from Keycloak (M02). Declaring it keeps Spring Boot from creating
     * a default user with a generated password.
     */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
