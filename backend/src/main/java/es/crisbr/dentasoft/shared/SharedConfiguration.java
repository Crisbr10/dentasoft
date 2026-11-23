package es.crisbr.dentasoft.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DentasoftUrls.class)
class SharedConfiguration {}
