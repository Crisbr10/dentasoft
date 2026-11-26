package es.crisbr.dentasoft.identity.internal;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers users on their first access ({@link UserRegistrationFilter}). */
@Configuration(proxyBeanMethods = false)
class UserRegistrationConfiguration {

    /** How many recently seen users each instance remembers: about 16 bytes each, a few hundred kilobytes in all. */
    static final int REMEMBERED_USERS = 10_000;

    @Bean
    FilterRegistrationBean<UserRegistrationFilter> userRegistrationFilter(UserAccounts accounts) {
        FilterRegistrationBean<UserRegistrationFilter> registration = new FilterRegistrationBean<>(
                new UserRegistrationFilter(accounts, new RegisteredUsers(REMEMBERED_USERS)));
        registration.setOrder(UserRegistrationFilter.ORDER);
        return registration;
    }
}
