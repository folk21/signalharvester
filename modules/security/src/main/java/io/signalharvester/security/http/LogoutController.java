package io.signalharvester.security.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;

/** Clears stateless browser authentication and CSRF cookies after an authenticated logout request. */
@Requires(property = "micronaut.security.enabled", value = "true")
@Controller("/api/v1/auth")
public final class LogoutController {

    private final String authenticationCookieName;
    private final String csrfCookieName;
    private final boolean secureCookies;

    public LogoutController(
            @Value("${micronaut.security.token.cookie.cookie-name:SIGNALHARVESTER_AUTH}")
                    String authenticationCookieName,
            @Value("${micronaut.security.csrf.cookie-name:XSRF-TOKEN}") String csrfCookieName,
            @Value("${micronaut.security.token.cookie.cookie-secure:false}") boolean secureCookies) {
        this.authenticationCookieName = authenticationCookieName;
        this.csrfCookieName = csrfCookieName;
        this.secureCookies = secureCookies;
    }

    /** Returns success and expires both browser security cookies immediately. */
    @Post("/logout")
    public HttpResponse<?> logout() {
        MutableHttpResponse<?> response = HttpResponse.ok();
        response.cookie(expiredCookie(authenticationCookieName, true));
        response.cookie(expiredCookie(csrfCookieName, false));
        return response;
    }

    private Cookie expiredCookie(String name, boolean httpOnly) {
        return Cookie.of(name, "")
                .path("/")
                .maxAge(0)
                .httpOnly(httpOnly)
                .secure(secureCookies)
                .sameSite(SameSite.Strict);
    }
}
