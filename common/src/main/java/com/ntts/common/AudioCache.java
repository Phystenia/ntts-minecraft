package com.ntts.common;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** A bounded, TTL-aware LRU cache for decoded PCM samples. */
final class AudioCache {
    private final boolean enabled;
    private final long maximumBytes;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long usedBytes;

    AudioCache(boolean enabled, long maximumBytes, long ttlMillis) {
        this(enabled, maximumBytes, ttlMillis, System::currentTimeMillis);
    }

    AudioCache(boolean enabled, long maximumBytes, long ttlMillis, LongSupplier clock) {
        this.enabled = enabled;
        this.maximumBytes = maximumBytes;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    synchronized short[] get(String key) {
        if (!enabled) {
            return null;
        }
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.getAsLong() - entry.createdAt >= ttlMillis) {
            entries.remove(key);
            usedBytes -= entry.samples.length * (long) Short.BYTES;
            return null;
        }
        return entry.samples;
    }

    synchronized long put(String key, short[] samples) {
        if (!enabled) {
            return 0L;
        }
        long sampleBytes = samples.length * (long) Short.BYTES;
        if (samples.length == 0 || sampleBytes > maximumBytes) {
            return 0L;
        }
        CacheEntry previous = entries.put(key, new CacheEntry(samples, clock.getAsLong()));
        if (previous != null) {
            usedBytes -= previous.samples.length * (long) Short.BYTES;
        }
        usedBytes += sampleBytes;
        long evictions = 0L;
        Iterator<Map.Entry<String, CacheEntry>> iterator = entries.entrySet().iterator();
        while (usedBytes > maximumBytes && iterator.hasNext()) {
            Map.Entry<String, CacheEntry> eldest = iterator.next();
            usedBytes -= eldest.getValue().samples.length * (long) Short.BYTES;
            iterator.remove();
            evictions++;
        }
        return evictions;
    }

    synchronized void clear() {
        entries.clear();
        usedBytes = 0L;
    }

    private static final class CacheEntry {
        private final short[] samples;
        private final long createdAt;

        private CacheEntry(short[] samples, long createdAt) {
            this.samples = samples;
            this.createdAt = createdAt;
        }
    }
}
