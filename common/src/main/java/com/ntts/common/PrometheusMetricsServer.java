package com.ntts.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class PrometheusMetricsServer implements AutoCloseable {
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final Logger logger;
    private final Supplier<String> metricsSupplier;
    private final Object lifecycleLock = new Object();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(
            daemonThreadFactory("ntts-metrics-lifecycle")
    );

    private volatile HttpServer server;
    private volatile ExecutorService requestExecutor;
    private volatile boolean closed;
    private volatile String error = "none";
    private volatile String bindAddress = MetricsConfiguration.DEFAULT_BIND_ADDRESS;
    private volatile int configuredPort = MetricsConfiguration.DEFAULT_PORT;
    private volatile int boundPort = -1;

    PrometheusMetricsServer(Logger logger, Supplier<String> metricsSupplier) {
        this.logger = logger;
        this.metricsSupplier = metricsSupplier;
    }

    void apply(MetricsConfiguration configuration) {
        bindAddress = configuration.getBindAddress();
        configuredPort = configuration.getPort();
        if (!configuration.isEnabled() && server == null) {
            error = "none";
            return;
        }
        try {
            lifecycleExecutor.execute(() -> applyOnLifecycleThread(configuration));
        } catch (RuntimeException exception) {
            error = "lifecycle-unavailable";
            logger.warn("Could not schedule /N/TTS metrics endpoint lifecycle update", exception);
        }
    }

    boolean isRunning() {
        return server != null;
    }

    int getBoundPort() {
        return boundPort;
    }

    String getStatus() {
        if (isRunning()) {
            return "running on " + bindAddress + ":" + boundPort;
        }
        if (!"none".equals(error)) {
            return "unavailable (" + error + ")";
        }
        return "disabled";
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            stopOnLifecycleThread();
        }
        lifecycleExecutor.shutdownNow();
    }

    private void applyOnLifecycleThread(MetricsConfiguration configuration) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            stopOnLifecycleThread();
            if (!configuration.isEnabled()) {
                error = "none";
                return;
            }
            try {
                HttpServer replacement = HttpServer.create(
                        new InetSocketAddress(configuration.getBindAddress(), configuration.getPort()),
                        0
                );
                replacement.createContext("/", this::handleRequest);
                ExecutorService replacementExecutor = Executors.newFixedThreadPool(
                        1,
                        daemonThreadFactory("ntts-metrics-http")
                );
                replacement.setExecutor(replacementExecutor);
                replacement.start();
                server = replacement;
                requestExecutor = replacementExecutor;
                boundPort = replacement.getAddress().getPort();
                error = "none";
                logger.info("Started /N/TTS Prometheus metrics endpoint on {}:{}", configuration.getBindAddress(), boundPort);
            } catch (BindException exception) {
                error = "port-bind-failed";
                logger.error("Could not bind /N/TTS metrics endpoint to {}:{}", configuration.getBindAddress(), configuration.getPort(), exception);
            } catch (IOException | RuntimeException exception) {
                error = "startup-failed";
                logger.error("Could not start /N/TTS metrics endpoint on {}:{}", configuration.getBindAddress(), configuration.getPort(), exception);
            }
        }
    }

    private void stopOnLifecycleThread() {
        HttpServer oldServer = server;
        server = null;
        boundPort = -1;
        if (oldServer != null) {
            oldServer.stop(0);
        }
        ExecutorService oldExecutor = requestExecutor;
        requestExecutor = null;
        if (oldExecutor != null) {
            oldExecutor.shutdownNow();
        }
    }

    private void handleRequest(HttpExchange exchange) {
        int status;
        byte[] body;
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                status = 405;
                body = "method not allowed\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Allow", "GET");
            } else if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
                status = 404;
                body = "not found\n".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = metricsSupplier.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (Throwable exception) {
            logger.warn("Error while serving /N/TTS metrics request", exception);
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // The client may already have disconnected.
            }
        } finally {
            exchange.close();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
