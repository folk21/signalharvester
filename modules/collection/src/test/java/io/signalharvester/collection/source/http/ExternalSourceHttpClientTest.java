package io.signalharvester.collection.source.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.HttpClientRegistry;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExternalSourceHttpClientTest {

    @Test
    void shouldFetchAbsoluteUrlSynchronouslyAndApplyFilterHeaders() throws Exception {
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst(HttpHeaders.USER_AGENT));
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "collected".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (ApplicationContext context = applicationContext()) {
            ExternalSourceHttpClient client = context.getBean(ExternalSourceHttpClient.class);
            URI uri = loopbackUri(server, "/source?topic=java&level=senior");

            HttpResponse<byte[]> response = client.fetch(uri);

            assertEquals(200, response.code());
            assertArrayEquals("collected".getBytes(StandardCharsets.UTF_8), response.body());
            assertEquals("SignalHarvester", userAgent.get());
            assertEquals("topic=java&level=senior", rawQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReuseManagedClientAcrossDifferentAbsoluteHosts() throws Exception {
        HttpServer firstServer = textServer("first");
        HttpServer secondServer = textServer("second");
        firstServer.start();
        secondServer.start();

        try (ApplicationContext context = applicationContext()) {
            ExternalSourceHttpClient client = context.getBean(ExternalSourceHttpClient.class);

            HttpResponse<byte[]> first = client.fetch(loopbackUri(firstServer, "/source"));
            HttpResponse<byte[]> second = client.fetch(loopbackUri(secondServer, "/source"));

            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), first.body());
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), second.body());
        } finally {
            firstServer.stop(0);
            secondServer.stop(0);
        }
    }

    @Test
    void shouldNotApplyCollectionHeadersToUnmarkedHttpClientRequests() throws Exception {
        AtomicReference<String> userAgent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plain", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst(HttpHeaders.USER_AGENT));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try (ApplicationContext context = applicationContext()) {
            HttpClientRegistry<?> registry = context.getBean(HttpClientRegistry.class);
            registry.getDefaultClient().toBlocking().exchange(HttpRequest.GET(loopbackUri(server, "/plain")));

            assertNotEquals("SignalHarvester", userAgent.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldExposeErrorResponseThroughHttpClientResponseException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/busy", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        try (ApplicationContext context = applicationContext()) {
            ExternalSourceHttpClient client = context.getBean(ExternalSourceHttpClient.class);

            HttpClientResponseException failure = assertThrows(
                    HttpClientResponseException.class,
                    () -> client.fetch(loopbackUri(server, "/busy")));

            assertEquals(503, failure.getStatus().getCode());
            assertEquals("30", failure.getResponse().header("Retry-After"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectResponseLargerThanConfiguredMaximum() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = "larger-than-limit".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (ApplicationContext context = applicationContext(Map.of(
                "micronaut.http.client.max-content-length", 4))) {
            ExternalSourceHttpClient client = context.getBean(ExternalSourceHttpClient.class);

            assertThrows(HttpClientException.class, () -> client.fetch(loopbackUri(server, "/large")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFollowBoundedRedirectsForNormalSourceUrls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add(HttpHeaders.LOCATION, "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] body = "redirected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (ApplicationContext context = applicationContext()) {
            ExternalSourceHttpClient client = context.getBean(ExternalSourceHttpClient.class);

            HttpResponse<byte[]> response = client.fetch(loopbackUri(server, "/redirect"));

            assertEquals(200, response.code());
            assertArrayEquals("redirected".getBytes(StandardCharsets.UTF_8), response.body());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer textServer(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static URI loopbackUri(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static ApplicationContext applicationContext() {
        return applicationContext(Map.of());
    }

    private static ApplicationContext applicationContext(Map<String, Object> overrides) {
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("micronaut.http.client.follow-redirects", true);
        properties.put("micronaut.http.client.max-redirects", 5);
        properties.put("micronaut.http.client.allow-block-event-loop", false);
        properties.put("signalharvester.collection.max-concurrency", 2);
        properties.putAll(overrides);
        return ApplicationContext.run(properties);
    }
}
