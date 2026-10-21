package com.ntts.common;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsConfigurationTest {
    @Test
    void usesSafeDefaults() {
        MetricsConfiguration configuration = MetricsConfiguration.from(new Properties());

        assertFalse(configuration.isEnabled());
        assertEquals("127.0.0.1", configuration.getBindAddress());
        assertEquals(9464, configuration.getPort());
    }

    @Test
    void acceptsEphemeralPortAndExplicitBindAddress() {
        Properties properties = new Properties();
        properties.setProperty("metrics_enabled", "true");
        properties.setProperty("metrics_bind_address", "localhost");
        properties.setProperty("metrics_port", "0");

        MetricsConfiguration configuration = MetricsConfiguration.from(properties);

        assertTrue(configuration.isEnabled());
        assertEquals("localhost", configuration.getBindAddress());
        assertEquals(0, configuration.getPort());
    }

    @Test
    void rejectsInvalidPortsAndAddresses() {
        assertThrows(IllegalArgumentException.class, () -> MetricsConfiguration.parsePort("1023"));
        assertThrows(IllegalArgumentException.class, () -> MetricsConfiguration.parsePort("65536"));

        Properties properties = new Properties();
        properties.setProperty("metrics_bind_address", "not a valid address");
        assertThrows(IllegalArgumentException.class, () -> MetricsConfiguration.from(properties));
    }
}
