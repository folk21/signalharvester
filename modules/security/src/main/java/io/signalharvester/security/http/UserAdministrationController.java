package io.signalharvester.security.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.validation.Validated;
import io.signalharvester.security.application.UserAccountOperations;
import io.signalharvester.security.model.UserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements ADMIN-only persistent identity and role administration. */
@Validated
@Requires(property = "micronaut.security.enabled", value = "true")
@Controller("/api/v1/admin/users")
@ExecuteOn(TaskExecutors.BLOCKING)
public class UserAdministrationController {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserAdministrationController.class);

    private final UserAccountOperations operations;

    public UserAdministrationController(UserAccountOperations operations) {
        this.operations = operations;
    }

    /** Lists all persisted identities without password hashes. */
    @Get
    public List<UserAccountResponse> list() {
        return operations.list().stream().map(UserAccountResponse::from).toList();
    }

    /** Creates a new identity and applies type-specific baseline roles. */
    @Post
    public HttpResponse<UserAccountResponse> create(
            @Body @Valid UserCreateRequest request,
            Authentication authentication) {
        UserAccountResponse response = UserAccountResponse.from(operations.create(request.toCommand()));
        LOGGER.info("User created actorPrincipalId={} targetUserId={}", authentication.getName(), response.id());
        return HttpResponse.created(response);
    }

    /** Returns one persisted identity. */
    @Get("/{userId}")
    public UserAccountResponse get(@PathVariable UUID userId) {
        return UserAccountResponse.from(operations.get(UserId.of(userId)));
    }

    /** Replaces enabled state and explicit role assignments for an existing identity. */
    @Put("/{userId}")
    public UserAccountResponse update(
            @PathVariable UUID userId,
            @Body @Valid UserUpdateRequest request,
            Authentication authentication) {
        UserAccountResponse response = UserAccountResponse.from(operations.update(UserId.of(userId), request.toCommand()));
        LOGGER.info("User updated actorPrincipalId={} targetUserId={}", authentication.getName(), userId);
        return response;
    }
}
