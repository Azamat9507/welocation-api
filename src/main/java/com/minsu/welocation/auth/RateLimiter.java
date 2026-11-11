package com.minsu.welocation.auth;

import com.minsu.welocation.common.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Fixed one-minute windows kept in memory. Good enough for a single instance; with more than one
// instance the counters would have to move to Redis.
@Slf4j
@Component
public class RateLimiter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    // ~100 bytes per entry, so the cap keeps the map around 10 MB however many addresses show up
    static final int MAX_KEYS = 100_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    private record Window(long start, int count) {
    }

    public RateLimiter(Clock clock) {
        this.clock = clock;
        this.lastSweep = clock.millis();
    }

    public void check(String key, int perMinute) {
        long now = clock.millis();
        if (!windows.containsKey(key) && windows.size() >= MAX_KEYS) {
            sweepAtMostOncePerWindow(now);
            if (windows.size() >= MAX_KEYS) {
                // Full of live keys means someone is spraying addresses or emails. Refusing new keys
                // keeps memory bounded; letting them through would switch the limit off mid-attack.
                throw new TooManyRequestsException(WINDOW_MILLIS / 1000);
            }
        }
        Window window = windows.compute(key, (k, old) -> old == null || now - old.start() >= WINDOW_MILLIS
                ? new Window(now, 1)
                : new Window(old.start(), old.count() + 1));
        if (window.count() > perMinute) {
            long waitMillis = window.start() + WINDOW_MILLIS - now;
            throw new TooManyRequestsException(Math.max(1, (waitMillis + 999) / 1000));
        }
    }

    // a full scan is O(n), so it runs once per window at most, not on every call
    private synchronized void sweepAtMostOncePerWindow(long now) {
        if (now - lastSweep < WINDOW_MILLIS) {
            return;
        }
        lastSweep = now;
        windows.values().removeIf(w -> now - w.start() >= WINDOW_MILLIS);
        log.warn("rate limiter key table was full; swept expired windows, {} live keys left", windows.size());
    }

    // One IPv6 user usually owns a whole /64, so counting single addresses would hand them 2^64 budgets.
    static String clientKey(String remoteAddr) {
        if (!remoteAddr.contains(":")) {
            return "ip:" + remoteAddr;
        }
        try {
            // a literal with ':' is parsed as IPv6 without any DNS lookup
            byte[] address = InetAddress.getByName(remoteAddr).getAddress();
            if (address.length == 16) {
                return "ip6:" + HexFormat.of().formatHex(address, 0, 8);
            }
            return "ip:" + remoteAddr;
        } catch (UnknownHostException e) {
            return "ip:" + remoteAddr;
        }
    }
}
