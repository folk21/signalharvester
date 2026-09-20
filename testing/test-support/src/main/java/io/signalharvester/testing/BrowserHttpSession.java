package io.signalharvester.testing;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Provides a standards-based cookie-aware HTTP session for browser-style integration tests. */
public final class BrowserHttpSession implements AutoCloseable {
    private final URI baseUri;
    private final Duration requestTimeout;
    private final CookieManager cookieManager;
    private final HttpClient client;

    public BrowserHttpSession(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Creates a request builder scoped to this session's origin and request timeout. */
    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(requestTimeout);
    }

    /** Sends one request while the JDK cookie handler applies and updates the session cookie store. */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return client.send(request, handler);
    }

    /** Returns a non-expired cookie from this session when present. */
    public Optional<HttpCookie> cookie(String name) {
        Objects.requireNonNull(name, "name");
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> !cookie.hasExpired())
                .filter(cookie -> cookie.getName().equals(name))
                .findFirst();
    }

    /** Returns a required cookie or fails with the names currently held by the session. */
    public HttpCookie requireCookie(String name) {
        return cookie(name).orElseThrow(() -> new AssertionError(
                "Missing cookie " + name + "; available cookies=" + cookieManager.getCookieStore().getCookies().stream()
                        .filter(cookie -> !cookie.hasExpired())
                        .map(HttpCookie::getName)
                        .toList()));
    }

    /** Returns whether a non-expired cookie with the supplied name is present. */
    public boolean hasCookie(String name) {
        return cookie(name).isPresent();
    }

    /** Closes the underlying HTTP client and its pooled connections. */
    @Override
    public void close() {
        client.close();
    }
}
