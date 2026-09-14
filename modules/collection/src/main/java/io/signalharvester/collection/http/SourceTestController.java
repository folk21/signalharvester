package io.signalharvester.collection.http;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.signalharvester.collection.sourcetest.SourceTester;
import io.signalharvester.configuration.api.SourceId;
import java.util.Objects;
import java.util.UUID;

/** Exposes bounded diagnostic testing for persisted external-source configuration. */
@Controller("/api/v1/sources")
@ExecuteOn(TaskExecutors.BLOCKING)
public final class SourceTestController {

    private final SourceTester sourceTester;

    public SourceTestController(SourceTester sourceTester) {
        this.sourceTester = Objects.requireNonNull(sourceTester, "sourceTester");
    }

    /** Tests one source through the normal fetch/extraction boundary without publishing application events. */
    @Post("/{sourceId}/test")
    public SourceTestResponse test(@PathVariable UUID sourceId) {
        return SourceTestResponse.from(sourceTester.test(SourceId.of(sourceId)));
    }
}
