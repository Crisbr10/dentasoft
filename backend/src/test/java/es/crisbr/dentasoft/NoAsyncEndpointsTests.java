package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * No endpoint answers asynchronously. The active clinic ({@code TenantContext}) and the user are bound to the thread
 * of the request and are not passed to other threads on purpose, so the part of an asynchronous answer that runs
 * elsewhere would have no clinic, or the wrong user. Every handler of the application is checked, also the ones
 * wrapped in a {@code ResponseEntity}.
 */
@IntegrationTest
class NoAsyncEndpointsTests {

    private static final List<Class<?>> ASYNC_TYPES = List.of(
            Callable.class,
            DeferredResult.class,
            WebAsyncTask.class,
            CompletionStage.class,
            Future.class,
            ResponseBodyEmitter.class,
            StreamingResponseBody.class,
            Publisher.class);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    @Test
    void noHandlerReturnsAnAsynchronousResult() {
        assertThat(handlers.getHandlerMethods().values())
                .isNotEmpty()
                .allSatisfy(handler -> assertThat(isAsync(ResolvableType.forMethodReturnType(handler.getMethod())))
                        .as(describe(handler))
                        .isFalse());
    }

    /** The check itself: it would catch the usual asynchronous answers, and lets ordinary ones through. */
    @Test
    void recognisesAsynchronousResults() {
        assertThat(isAsync(ResolvableType.forClass(SseEmitter.class))).isTrue();
        assertThat(isAsync(ResolvableType.forClassWithGenerics(CompletableFuture.class, String.class)))
                .isTrue();
        assertThat(isAsync(ResolvableType.forClassWithGenerics(
                        ResponseEntity.class, ResolvableType.forClassWithGenerics(DeferredResult.class, String.class))))
                .isTrue();
        assertThat(isAsync(ResolvableType.forClassWithGenerics(ResponseEntity.class, String.class)))
                .isFalse();
    }

    private static boolean isAsync(ResolvableType type) {
        Class<?> raw = type.toClass();
        if (ASYNC_TYPES.stream().anyMatch(async -> async.isAssignableFrom(raw))) {
            return true;
        }
        // ResponseEntity<DeferredResult<…>> and the like.
        for (ResolvableType generic : type.getGenerics()) {
            if (generic != ResolvableType.NONE && isAsync(generic)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(HandlerMethod handler) {
        return handler.getBeanType().getName() + "." + handler.getMethod().getName() + " answers asynchronously";
    }
}
