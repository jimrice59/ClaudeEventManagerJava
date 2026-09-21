package com.eventmanager.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Redis-backed sliding-window rate limiter. Each accepted request for a given key is recorded as
 * a member of a Redis sorted set (ZSET), scored by its timestamp in epoch millis. Every check
 * atomically — via a single Lua script (EVAL), so the prune-count-conditionally-add sequence
 * can't race across concurrent requests — removes members older than the current window before
 * counting what's left. This is a true rolling window, not a fixed bucket that resets on a clock
 * boundary: a fixed-window counter lets a client burst up to 2x the limit by timing requests
 * around the reset instant, which is exactly the burst behavior this is meant to prevent.
 * <p>
 * The key's TTL is (re)set to the window length on every call ("TTL per time window"), so a key
 * that goes idle for a full window expires and cleans itself up automatically — no separate
 * eviction job is needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String KEY_PREFIX = "rate-limit:";

    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)

            if count < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window)
                return 1
            end

            redis.call('PEXPIRE', key, window)
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    /**
     * Returns true if the caller identified by {@code identifier} (e.g. {@code "ip:1.2.3.4"}) may
     * proceed, false if it has already made {@link RateLimitProperties#getMaxRequests()} requests
     * within the trailing {@link RateLimitProperties#getWindowDuration()}.
     * <p>
     * Fails open (returns true) if Redis is unreachable: a rate limiter's own datastore being
     * down should never itself take the whole API down, which is what would happen if every
     * request were rejected on a caught exception instead.
     */
    public boolean isAllowed(String identifier) {
        String key = KEY_PREFIX + identifier;
        long now = System.currentTimeMillis();
        long windowMillis = properties.getWindowDuration().toMillis();
        String member = now + ":" + UUID.randomUUID();

        try {
            Long result = redisTemplate.execute(SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(now), String.valueOf(windowMillis),
                    String.valueOf(properties.getMaxRequests()), member);
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Rate limiter failed open for key='{}': {}", key, e.getMessage());
            return true;
        }
    }
}
