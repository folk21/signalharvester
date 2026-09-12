package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicronautBlockingExecutorTest {

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

    @Test
    void shouldResolveCollectionCoordinatorWithModuleOwnedClock() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "micronaut.executors.blocking.virtual", true,
                "kafka.enabled", false))) {
            assertNotNull(context.getBean(SourceFetchCoordinator.class));
        }
    }
}
