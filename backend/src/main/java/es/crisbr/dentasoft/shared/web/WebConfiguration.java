package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.DentasoftUrls;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Web configuration shared by every module: OpenAPI, CORS and the cross-cutting filters, in this order:
 * request ID, security headers, CORS, request size, (Spring Security from M02-T02), rate limit.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CorsProperties.class, HttpProperties.class, RateLimitProperties.class})
class WebConfiguration {

    static final List<String> CORS_PATHS = List.of("/api/**", "/actuator/health/**");

    /** The OpenAPI server URL is the public API URL of the current environment. */
    @Bean
    OpenAPI dentasoftOpenApi(DentasoftUrls urls) {
        return new OpenAPI()
                .info(new Info().title("Dentasoft API").version("v1"))
                .servers(List.of(new Server().url(urls.api())));
    }

    /**
     * Browser access from {@code dentasoft.cors.allowed-origins} only. No credentials: the API uses Bearer tokens,
     * never cookies. Spring Security (M02-T02) must reuse this bean instead of defining its own rules.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Clinic-Id", RequestIdFilter.HEADER));
        cors.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.RETRY_AFTER));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CORS_PATHS.forEach(path -> source.registerCorsConfiguration(path, cors));
        return source;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return registration(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        return registration(new SecurityHeadersFilter(), Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return registration(new CorsFilter(corsConfigurationSource), Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(
            HttpProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return registration(
                new RequestSizeFilter(properties.maxRequestBodySize().toBytes(), exceptionResolver),
                Ordered.HIGHEST_PRECEDENCE + 3);
    }

    @Bean
    ClientIpResolver clientIpResolver(HttpProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }

    @Bean
    RedisBuckets redisBuckets(LettuceConnectionFactory connectionFactory) {
        return new RedisBuckets(connectionFactory);
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, RedisBuckets redisBuckets, MeterRegistry registry) {
        return new RateLimiter(properties, redisBuckets, registry, InstantSource.system());
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        FilterRegistrationBean<RateLimitFilter> registration = registration(
                new RateLimitFilter(rateLimiter, clientIpResolver, exceptionResolver, properties),
                RateLimitFilter.ORDER);
        registration.setEnabled(properties.enabled());
        return registration;
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registration(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
