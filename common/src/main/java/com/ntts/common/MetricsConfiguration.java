package com.ntts.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

final class MetricsConfiguration {
    static final boolean DEFAULT_ENABLED = false;
    static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    static final int DEFAULT_PORT = 9464;

    private final boolean enabled;
    private final String bindAddress;
    private final int port;

    private MetricsConfiguration(boolean enabled, String bindAddress, int port) {
        this.enabled = enabled;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    static MetricsConfiguration from(Properties properties) {
        boolean enabled = parseBoolean(properties, "metrics_enabled", DEFAULT_ENABLED);
        String bindAddress = properties.getProperty("metrics_bind_address", DEFAULT_BIND_ADDRESS).trim();
        if (bindAddress.isEmpty() || bindAddress.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("metrics_bind_address must be a non-empty address");
        }
        try {
            InetAddress.getByName(bindAddress);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("metrics_bind_address is invalid", exception);
        }
        int port = parsePort(properties.getProperty("metrics_port", Integer.toString(DEFAULT_PORT)));
        return new MetricsConfiguration(enabled, bindAddress, port);
    }

    boolean isEnabled() {
        return enabled;
    }

    String getBindAddress() {
        return bindAddress;
    }

    int getPort() {
        return port;
    }

    static int parsePort(String value) {
        final int port;
        try {
            port = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("metrics_port must be 0 or between 1024 and 65535", exception);
        }
        if (port != 0 && (port < 1024 || port > 65535)) {
            throw new IllegalArgumentException("metrics_port must be 0 or between 1024 and 65535");
        }
        return port;
    }

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be 'true' or 'false'");
        }
        return Boolean.parseBoolean(value);
    }
}
