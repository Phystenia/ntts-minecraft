package com.ntts.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskAudioCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPcmUnderDigestOnlyFileName() throws IOException {
        DiskAudioCache cache = new DiskAudioCache(
                true,
                temporaryDirectory,
                1_024L,
                60_000L,
                LoggerFactory.getLogger(DiskAudioCacheTest.class)
        );
        short[] samples = {5, -7, 12};

        cache.put("narrator\u0000plain text must not become a file name", samples);

        assertArrayEquals(samples, cache.get("narrator\u0000plain text must not become a file name"));
        Path entry;
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            entry = files.findFirst().orElseThrow();
        }
        assertTrue(entry.getFileName().toString().matches("[0-9a-f]{64}\\.pcm"));
        assertFalse(entry.getFileName().toString().contains("plain text"));
    }
}
