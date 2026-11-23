package es.crisbr.dentasoft.shared.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * HTTP limits and the proxies whose forwarding headers are trusted.
 *
 * @param maxRequestBodySize largest accepted body for non-multipart requests; multipart uploads are limited by
 *     {@code spring.servlet.multipart.*}
 * @param trustedProxies IP addresses or CIDR ranges ({@code DENTASOFT_TRUSTED_PROXIES}, comma separated) allowed
 *     to set {@code X-Forwarded-For} and {@code CF-Connecting-IP}. Empty by default: the connection address is used.
 */
@ConfigurationProperties("dentasoft.http")
record HttpProperties(@DefaultValue("1MB") DataSize maxRequestBodySize, List<String> trustedProxies) {

    HttpProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
