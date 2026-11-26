package es.crisbr.dentasoft.identity.internal.keycloak;

import es.crisbr.dentasoft.shared.DentasoftUrls;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link KeycloakAdmin} of the application. Building it asks Keycloak for nothing: the first token is requested on
 * the first call, so the application starts while Keycloak is down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfiguration {

    @Bean
    KeycloakAdmin keycloakAdmin(KeycloakAdminProperties properties, DentasoftUrls urls) {
        return new RestKeycloakAdmin(properties, urls);
    }
}
