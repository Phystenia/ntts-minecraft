package com.ntts.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AudioCacheTest {
    @Test
    void expiresEntriesUsingConfiguredTtl() {
        AtomicLong clock = new AtomicLong(1_000L);
        AudioCache cache = new AudioCache(true, 64L, 100L, clock::get);
        short[] samples = {1, 2};

        cache.put("key", samples);
        assertArrayEquals(samples, cache.get("key"));

        clock.addAndGet(100L);
        assertNull(cache.get("key"));
    }

    @Test
    void evictsLeastRecentlyUsedEntryWhenFull() {
        AudioCache cache = new AudioCache(true, 4L, 10_000L, () -> 1_000L);

        assertEquals(0L, cache.put("first", new short[]{1, 2}));
        assertEquals(1L, cache.put("second", new short[]{3, 4}));

        assertNull(cache.get("first"));
        assertArrayEquals(new short[]{3, 4}, cache.get("second"));
    }
}
