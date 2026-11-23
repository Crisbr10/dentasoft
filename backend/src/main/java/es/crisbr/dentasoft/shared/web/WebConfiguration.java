package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.DentasoftUrls;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
class WebConfiguration {

    /** The OpenAPI server URL is the public API URL of the current environment. */
    @Bean
    OpenAPI dentasoftOpenApi(DentasoftUrls urls) {
        return new OpenAPI()
                .info(new Info().title("Dentasoft API").version("v1"))
                .servers(List.of(new Server().url(urls.api())));
    }
}
