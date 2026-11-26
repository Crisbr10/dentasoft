package es.crisbr.dentasoft.identity.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The active clinic of requests to the clinic API ({@link ActiveClinicFilter}). */
@Configuration(proxyBeanMethods = false)
class ActiveClinicConfiguration {

    /**
     * {@link ActiveClinicAccess} is looked up on the first request, as {@link UserRegistrationConfiguration} does, so
     * the filter gets the service with its transaction and security proxies.
     */
    @Bean
    FilterRegistrationBean<ActiveClinicFilter> activeClinicFilter(
            ObjectProvider<ActiveClinicAccess> access,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        FilterRegistrationBean<ActiveClinicFilter> registration =
                new FilterRegistrationBean<>(new ActiveClinicFilter(access, exceptionResolver));
        registration.setOrder(ActiveClinicFilter.ORDER);
        return registration;
    }
}
