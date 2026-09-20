package io.signalharvester.configuration.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.application.MonitoringProfileConfigurationOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Implements monitoring-profile REST administration through configuration-owned use cases. */
@Validated
@Controller("/api/v1/monitoring-profiles")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MonitoringProfileController {
    private final MonitoringProfileConfigurationOperations operations;

    public MonitoringProfileController(MonitoringProfileConfigurationOperations operations) {
        this.operations = operations;
    }

    @Get
    public List<MonitoringProfileResponse> list() {
        return operations.list().stream().map(MonitoringProfileResponse::from).toList();
    }

    @Post
    public HttpResponse<MonitoringProfileResponse> create(
            @Body @Valid @NotNull MonitoringProfileUpsertRequest request) {
        return HttpResponse.created(MonitoringProfileResponse.from(operations.create(request.toCommand())));
    }

    @Get("/{profileId}")
    public MonitoringProfileResponse get(@PathVariable UUID profileId) {
        return MonitoringProfileResponse.from(operations.get(MonitoringProfileId.of(profileId)));
    }

    @Put("/{profileId}")
    public MonitoringProfileResponse update(
            @PathVariable UUID profileId,
            @Body @Valid @NotNull MonitoringProfileUpsertRequest request) {
        return MonitoringProfileResponse.from(
                operations.update(MonitoringProfileId.of(profileId), request.toCommand()));
    }

    @Delete("/{profileId}")
    public HttpResponse<?> delete(@PathVariable UUID profileId) {
        operations.delete(MonitoringProfileId.of(profileId));
        return HttpResponse.noContent();
    }
}
