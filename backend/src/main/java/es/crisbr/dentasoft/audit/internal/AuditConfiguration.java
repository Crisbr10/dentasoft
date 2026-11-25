package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditService;
import es.crisbr.dentasoft.audit.AuditedRead;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;

/**
 * Wires the change listener ({@link AuditListenerRegistration}) and {@link AuditedRead}. Reads use Spring AOP alone:
 * a pointcut on the annotation and a plain {@code MethodInterceptor}, the same mechanism {@code @PreAuthorize} and
 * {@code @Transactional} use. No AspectJ, no new dependency (ADR 0012).
 */
@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

    /**
     * Before every Spring Security interceptor, and so before {@code @Transactional}, whose advisor comes last
     * ({@code Ordered.LOWEST_PRECEDENCE}): audit, then authorization, then the transaction.
     */
    static final int ORDER = AuthorizationInterceptorsOrder.PRE_FILTER.getOrder() - 1;

    /** Records changes on every entity manager factory: a post-processor, so it is never skipped. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BeanPostProcessor auditListenerRegistration() {
        return new AuditListenerRegistration();
    }

    /**
     * An infrastructure bean, so whichever auto-proxy creator is active applies it. Static, and with a lazy {@link
     * AuditService}, so it does not bring the persistence beans up while post-processors are still being created.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor auditedReadAdvisor(ObjectProvider<AuditService> audit) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(AuditedRead.class), new AuditedReadInterceptor(audit));
        advisor.setOrder(ORDER);
        return advisor;
    }
}
