package io.signalharvester.security.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records authorization rejections without exposing credentials or complete JWT contents. */
@ServerFilter("/api/v1/**")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@Requires(property = "micronaut.security.enabled", value = "true")
public final class SecurityAccessAuditFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAccessAuditFilter.class);

    /** Logs authentication and authorization rejections after the security filter has produced the HTTP response. */
    @ResponseFilter
    void observeDeniedAccess(HttpRequest<?> request, HttpResponse<?> response) {
        HttpStatus status = response.getStatus();
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            String reason = status == HttpStatus.UNAUTHORIZED ? "authentication-required" : "insufficient-role";
            LOGGER.info(
                    "Security access denied method={} path={} status={} reason={}",
                    request.getMethod(),
                    request.getPath(),
                    status.getCode(),
                    reason);
        }
    }
}
