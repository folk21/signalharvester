package io.signalharvester.collection.source.http;

import io.micronaut.http.HttpResponse;
import java.net.URI;

/**
 * Internal HTTP transport boundary used by the collection adapter to fetch one configured source.
 *
 * <p>The implementation is intentionally separate from Micronaut's declarative {@code @Client}
 * model because SignalHarvester sources may point to arbitrary absolute HTTP(S) hosts discovered
 * from persisted configuration.</p>
 */
@FunctionalInterface
public interface ExternalSourceHttpClient {

    /**
     * Performs a GET against one absolute configured source URL.
     *
     * @param uri absolute source URL
     * @return complete HTTP response
     */
    HttpResponse<byte[]> fetch(URI uri);
}
