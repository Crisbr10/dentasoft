package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditService;
import es.crisbr.dentasoft.audit.AuditedEntityId;
import es.crisbr.dentasoft.audit.AuditedRead;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/**
 * Records the read of an {@link AuditedRead} method once it has returned, and a denied one when {@code @PreAuthorize}
 * rejects it. It runs outside both {@code @PreAuthorize} and {@code @Transactional} ({@code AuditConfiguration}), so
 * the read's transaction has ended, and its connection is back in the pool, before the entry is written.
 *
 * <p>If the entry cannot be written, the method throws and its result is lost: nothing read goes out unrecorded.
 */
final class AuditedReadInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditedReadInterceptor.class);

    private final ObjectProvider<AuditService> audit;

    AuditedReadInterceptor(ObjectProvider<AuditService> audit) {
        this.audit = audit;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = mostSpecific(invocation);
        AuditedRead read = AnnotatedElementUtils.findMergedAnnotation(method, AuditedRead.class);
        if (read == null) {
            return invocation.proceed();
        }
        AuditEntityType entityType = read.value();
        UUID entityId = entityId(method, invocation.getArguments());
        Object result;
        try {
            result = invocation.proceed();
        } catch (AccessDeniedException | AuthenticationException denied) {
            // Denied for want of a role, or of any authentication at all.
            try {
                audit.getObject().recordDeniedRead(entityType, entityId);
            } catch (RuntimeException failure) {
                // The read failed anyway; the caller gets the denial, and the lost entry is logged, by id only.
                log.error("Could not record a denied read of {} {}", entityType, entityId, failure);
                denied.addSuppressed(failure);
            }
            throw denied;
        }
        audit.getObject().recordRead(entityType, entityId);
        return result;
    }

    private static Method mostSpecific(MethodInvocation invocation) {
        Object target = invocation.getThis();
        return target == null
                ? invocation.getMethod()
                : AopUtils.getMostSpecificMethod(invocation.getMethod(), AopUtils.getTargetClass(target));
    }

    /** The argument of the one {@link AuditedEntityId} parameter: an audited read must say what it reads. */
    static UUID entityId(Method method, Object[] arguments) {
        Parameter[] parameters = method.getParameters();
        Integer index = null;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(AuditedEntityId.class)) {
                if (index != null || parameters[i].getType() != UUID.class) {
                    throw misused(method);
                }
                index = i;
            }
        }
        if (index == null) {
            throw misused(method);
        }
        if (!(arguments[index] instanceof UUID entityId)) {
            throw new IllegalArgumentException("The id of an audited read cannot be null: " + method.getName());
        }
        return entityId;
    }

    private static IllegalStateException misused(Method method) {
        return new IllegalStateException(
                "@AuditedRead method " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                        + " needs exactly one UUID parameter marked @AuditedEntityId");
    }
}
