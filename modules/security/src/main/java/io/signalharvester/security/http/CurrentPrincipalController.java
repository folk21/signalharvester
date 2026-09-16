package io.signalharvester.security.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.authentication.Authentication;

/** Exposes the authenticated principal and explicit JWT role set to the browser. */
@Requires(property = "micronaut.security.enabled", value = "true")
@Controller("/api/v1/auth")
public final class CurrentPrincipalController {

    /** Returns the current authenticated identity. */
    @Get("/me")
    public CurrentPrincipalResponse currentPrincipal(Authentication authentication) {
        return CurrentPrincipalResponse.from(authentication);
    }
}
