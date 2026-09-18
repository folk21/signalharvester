package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the executor wiring consumed by {@link CollectionRunService} and collection HTTP/application work
 * so blocking operations run on the configured Micronaut blocking executor rather than an event loop.
 *
 * <p>Related specification: {@code backend-project-structure}.</p>
 *
 * <p>Feature: {@code RUNTIME.CONCURRENCY}.</p>
 */
class MicronautBlockingExecutorTest {

    /**
     * Use virtual threads for blocking executor on java 21.
     */
    @Test
    void shouldUseVirtualThreadsForBlockingExecutorOnJava21() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "micronaut.executors.blocking.virtual", true,
                "kafka.enabled", false))) {
            ExecutorService executor = context.getBean(
                    ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));

            boolean virtual = executor.submit(() -> Thread.currentThread().isVirtual())
                    .get(2, TimeUnit.SECONDS);

            assertTrue(virtual);
        }
    }

    /** Resolve the framework scheduling boundary used by collection background work. */
    @Test
    void shouldExposeNamedTaskSchedulerForBackgroundWork() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "micronaut.executors.blocking.virtual", true,
                "kafka.enabled", false))) {
            TaskScheduler scheduler = context.getBean(
                    TaskScheduler.class, Qualifiers.byName(TaskExecutors.SCHEDULED));

            assertNotNull(scheduler);
        }
    }

    /**
     * Resolve collection coordinator with module-owned clock.
     */
    @Test
    void shouldResolveCollectionCoordinatorWithModuleOwnedClock() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "micronaut.executors.blocking.virtual", true,
                "kafka.enabled", false))) {
            assertNotNull(context.getBean(SourceFetchCoordinator.class));
        }
    }
}
