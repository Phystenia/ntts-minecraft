package com.ntts.common;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Small bounded PCM cache. File names contain only a SHA-256 digest, never the
 * original text or a credential.
 */
final class DiskAudioCache {
    private static final int MAGIC = 0x4E545453; // NTTS
    private static final int FORMAT_VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES;

    private final boolean enabled;
    private final Path directory;
    private final long maximumBytes;
    private final long ttlMillis;
    private final Logger logger;

    DiskAudioCache(boolean enabled, Path directory, long maximumBytes, long ttlMillis, Logger logger) {
        this.enabled = enabled;
        this.directory = directory;
        this.maximumBytes = maximumBytes;
        this.ttlMillis = ttlMillis;
        this.logger = logger;
    }

    short[] get(String key) {
        if (!enabled) {
            return null;
        }
        Path file = entryPath(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                invalidate(file);
                return null;
            }
            long createdAt = input.readLong();
            int sampleCount = input.readInt();
            if (isExpired(createdAt) || sampleCount < 0 || sampleCount > maximumBytes / Short.BYTES) {
                invalidate(file);
                return null;
            }
            short[] samples = new short[sampleCount];
            for (int index = 0; index < sampleCount; index++) {
                samples[index] = input.readShort();
            }
            return samples;
        } catch (IOException | RuntimeException exception) {
            logger.debug("Ignoring invalid /N/TTS disk cache entry: {}", exception.toString());
            invalidate(file);
            return null;
        }
    }

    void put(String key, short[] samples) {
        if (!enabled || samples == null || samples.length == 0) {
            return;
        }
        long bytes = encodedSize(samples.length);
        if (bytes > maximumBytes) {
            return;
        }
        Path target = entryPath(key);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(System.currentTimeMillis());
                output.writeInt(samples.length);
                for (short sample : samples) {
                    output.writeShort(sample);
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            evictOldEntries();
        } catch (IOException exception) {
            logger.debug("Could not write /N/TTS disk cache entry: {}", exception.toString());
            invalidate(temporary);
        }
    }

    private void evictOldEntries() {
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> entries = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".pcm"))
                    .forEach(entries::add);
            entries.sort(Comparator.comparingLong(this::lastModified));
            long used = 0L;
            for (Path entry : entries) {
                used += Files.size(entry);
            }
            for (Path entry : entries) {
                if (used <= maximumBytes) {
                    break;
                }
                long size = Files.size(entry);
                if (Files.deleteIfExists(entry)) {
                    used -= size;
                }
            }
        } catch (IOException exception) {
            logger.debug("Could not evict /N/TTS disk cache entries: {}", exception.toString());
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private boolean isExpired(long createdAt) {
        return createdAt <= 0L || System.currentTimeMillis() - createdAt >= ttlMillis;
    }

    private Path entryPath(String key) {
        return directory.resolve(sha256(key) + ".pcm");
    }

    private static long encodedSize(int sampleCount) {
        return HEADER_BYTES + sampleCount * (long) Short.BYTES;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private void invalidate(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            logger.debug("Could not remove invalid /N/TTS disk cache entry: {}", exception.toString());
        }
    }
}
