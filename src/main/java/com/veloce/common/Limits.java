package com.veloce.common;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Limits {
    private record Bucket(long until, int count) {}
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    private long cleanupAfter;

    public Limits(Clock clock) { this.clock = clock; }

    public synchronized void check(String key, int maximum, int seconds) {
        long now = clock.instant().getEpochSecond();
        if (now >= cleanupAfter) {
            buckets.values().removeIf(b -> b.until <= now);
            cleanupAfter = now + 60;
        }
        Bucket old = buckets.get(key);
        if ((old == null && buckets.size() >= 20_000) || (old != null && old.until > now && old.count >= maximum))
            throw new Problem(429, "RATE_LIMIT", "Too many requests. Try again shortly.");
        buckets.put(key, old == null || old.until <= now ? new Bucket(now + seconds, 1) : new Bucket(old.until, old.count + 1));
    }
}
