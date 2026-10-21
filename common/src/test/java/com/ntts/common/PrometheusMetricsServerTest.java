package com.ntts.common;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusMetricsServerTest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Test
    void servesMetricsAndHandlesUnsupportedRequests() throws Exception {
        PrometheusMetricsServer server = new PrometheusMetricsServer(
                LoggerFactory.getLogger(PrometheusMetricsServerTest.class),
                () -> "# TYPE ntts_up gauge\nntts_up 1\n"
        );
        try {
            server.apply(configuration(true, 0));
            await(server::isRunning);
            int port = server.getBoundPort();

            HttpResponse<String> metrics = request("GET", port, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(metrics.body().contains("ntts_up 1"));

            assertEquals(404, request("GET", port, "/other").statusCode());
            assertEquals(405, request("POST", port, "/metrics").statusCode());
        } finally {
            server.close();
        }
        await(() -> !server.isRunning());
    }

    @Test
    void reportsBindFailureWithoutThrowing() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            PrometheusMetricsServer server = new PrometheusMetricsServer(
                    LoggerFactory.getLogger(PrometheusMetricsServerTest.class),
                    () -> "ntts_up 1\n"
            );
            try {
                server.apply(configuration(true, occupied.getLocalPort()));
                await(() -> server.getStatus().contains("unavailable"));
                assertFalse(server.isRunning());
            } finally {
                server.close();
            }
        }
    }

    @Test
    void reloadStopsThePreviousListener() throws Exception {
        PrometheusMetricsServer server = new PrometheusMetricsServer(
                LoggerFactory.getLogger(PrometheusMetricsServerTest.class),
                () -> "ntts_up 1\n"
        );
        try {
            server.apply(configuration(true, 0));
            await(server::isRunning);
            server.apply(configuration(false, 0));
            await(() -> !server.isRunning());
        } finally {
            server.close();
        }
    }

    private static MetricsConfiguration configuration(boolean enabled, int port) {
        Properties properties = new Properties();
        properties.setProperty("metrics_enabled", Boolean.toString(enabled));
        properties.setProperty("metrics_bind_address", "127.0.0.1");
        properties.setProperty("metrics_port", Integer.toString(port));
        return MetricsConfiguration.from(properties);
    }

    private static HttpResponse<String> request(String method, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "condition was not reached before timeout");
    }
}
