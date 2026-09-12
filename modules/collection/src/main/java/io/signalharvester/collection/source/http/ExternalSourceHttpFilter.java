package io.signalharvester.collection.source.http;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies collection-specific technical headers and lightweight diagnostics to marked source calls.
 *
 * <p>The filter is registered with Micronaut's client pipeline globally because the underlying
 * managed client is not tied to one service id. It changes only requests explicitly marked by the
 * collection transport, so other Micronaut HTTP clients remain unaffected.</p>
 */
@ClientFilter
public final class ExternalSourceHttpFilter {

    static final String SOURCE_REQUEST_ATTRIBUTE = "signalharvester.collection.external-source";

    private static final Logger LOG = LoggerFactory.getLogger(ExternalSourceHttpFilter.class);
    private static final String USER_AGENT = "SignalHarvester";

    /**
     * Adds stable headers only to outbound requests owned by the collection transport.
     *
     * @param request mutable outbound request
     */
    @RequestFilter
    void prepareRequest(MutableHttpRequest<?> request) {
        if (!isExternalSourceRequest(request)) {
            return;
        }
        request.header(HttpHeaders.ACCEPT, "*/*");
        request.header(HttpHeaders.USER_AGENT, USER_AGENT);
    }

    /**
     * Records source error responses without exposing complete configured URLs in logs.
     *
     * @param request completed outbound request
     * @param response received response
     */
    @ResponseFilter
    void observeResponse(HttpRequest<?> request, HttpResponse<?> response) {
        if (!isExternalSourceRequest(request) || response.code() < 400) {
            return;
        }

        URI uri = request.getUri();
        LOG.debug(
                "External source request returned status={} scheme={} host={} port={}",
                response.code(),
                uri.getScheme(),
                uri.getHost(),
                uri.getPort());
    }

    private static boolean isExternalSourceRequest(HttpRequest<?> request) {
        return request.getAttribute(SOURCE_REQUEST_ATTRIBUTE, Boolean.class).orElse(false);
    }
}
