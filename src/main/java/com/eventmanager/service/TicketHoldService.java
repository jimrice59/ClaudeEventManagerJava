package com.eventmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ticket reservations ("holds") in Redis, each expiring on its own after
 * {@code ticket-reservation.hold-ttl}. A held ticket stays AVAILABLE in Postgres; "available to
 * reserve" means AVAILABLE in Postgres and not held here. So an expired hold needs no cleanup in
 * Postgres: the ticket simply stops being excluded. Only a purchase writes Postgres.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code hold:ticket:{ticketId}} — value {@code "<userId>:<expiresAtMillis>"}, with a PX TTL.
 *       Its existence is the hold; Redis deletes it when the TTL lapses.</li>
 *   <li>{@code holds:event:{eventId}} and {@code holds:user:{userId}} — sorted sets of held ticket
 *       ids scored by expiry, so an event's held tickets can be excluded from Postgres queries and
 *       a user's held tickets listed. Members past their expiry are pruned on every read (same
 *       expiry as the hold key, from Redis's own clock), and each set itself expires shortly after
 *       its newest hold, so nothing needs a background sweep.</li>
 * </ul>
 * Every multi-key change is one Lua script, so it's atomic. Any Redis error becomes
 * ReservationsUnavailableException (503): reservations fail closed rather than risk two people
 * holding one ticket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketHoldService {

    /** A live hold: who holds the ticket and when the hold expires. */
    public record Hold(Long userId, Instant expiresAt) {
    }

    public enum ReleaseResult { RELEASED, NOT_HELD, HELD_BY_OTHER }

    /**
     * KEYS: hold key, event index, user index. ARGV: userId, ttlMillis, ticketId, index TTL millis.
     * Returns the hold's expiry (epoch millis, from Redis's clock) if acquired, else 0.
     */
    private static final RedisScript<Long> HOLD_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local expires = now + tonumber(ARGV[2])
            if not redis.call('SET', KEYS[1], ARGV[1] .. ':' .. expires, 'NX', 'PX', ARGV[2]) then
                return 0
            end
            redis.call('ZADD', KEYS[2], expires, ARGV[3])
            redis.call('ZADD', KEYS[3], expires, ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            redis.call('PEXPIRE', KEYS[3], ARGV[4])
            return expires
            """, Long.class);

    /**
     * KEYS: hold key, event index, user index. ARGV: userId, ticketId.
     * Deletes the hold only if the caller holds it. Returns 1 released, 0 not held, -1 held by someone else.
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then
                return 0
            end
            if string.match(v, '^(%d+):') ~= ARGV[1] then
                return -1
            end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[2])
            redis.call('ZREM', KEYS[3], ARGV[2])
            return 1
            """, Long.class);

    /** KEYS: an index. Prunes members whose hold has expired (by Redis's clock), returns the rest. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> LIVE_MEMBERS_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            return redis.call('ZRANGE', KEYS[1], 0, -1)
            """, List.class);

    /** Index sets outlive their newest hold by this much, then Redis drops them. */
    private static final long INDEX_GRACE_MILLIS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final TicketReservationProperties properties;

    static String holdKey(Long ticketId) {
        return "hold:ticket:" + ticketId;
    }

    static String eventIndexKey(Long eventId) {
        return "holds:event:" + eventId;
    }

    static String userIndexKey(Long userId) {
        return "holds:user:" + userId;
    }

    /** Tries to hold a ticket for a user; returns the expiry if acquired, empty if someone already holds it. */
    public Optional<Instant> tryHold(Long eventId, Long ticketId, Long userId) {
        long ttlMillis = properties.getHoldTtl().toMillis();
        Long expires = redis(() -> redisTemplate.execute(HOLD_SCRIPT,
                List.of(holdKey(ticketId), eventIndexKey(eventId), userIndexKey(userId)),
                userId.toString(), Long.toString(ttlMillis), ticketId.toString(),
                Long.toString(ttlMillis + INDEX_GRACE_MILLIS)));
        if (expires == null || expires == 0) {
            return Optional.empty();
        }
        log.info("Hold placed on ticket id={} for user id={} until {}", ticketId, userId, Instant.ofEpochMilli(expires));
        return Optional.of(Instant.ofEpochMilli(expires));
    }

    /** Releases a hold, but only if this user holds it. */
    public ReleaseResult release(Long eventId, Long ticketId, Long userId) {
        Long result = redis(() -> redisTemplate.execute(RELEASE_SCRIPT,
                List.of(holdKey(ticketId), eventIndexKey(eventId), userIndexKey(userId)),
                userId.toString(), ticketId.toString()));
        if (result != null && result == 1) {
            log.info("Hold on ticket id={} released by user id={}", ticketId, userId);
            return ReleaseResult.RELEASED;
        }
        return result != null && result == -1 ? ReleaseResult.HELD_BY_OTHER : ReleaseResult.NOT_HELD;
    }

    /** The live hold on a ticket, if any. */
    public Optional<Hold> getHold(Long ticketId) {
        return Optional.ofNullable(parse(redis(() -> redisTemplate.opsForValue().get(holdKey(ticketId)))));
    }

    /** Live holds for several tickets in one round trip (MGET); tickets without a hold are absent. */
    public Map<Long, Hold> getHolds(Collection<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(ticketIds);
        List<String> values = redis(() -> redisTemplate.opsForValue().multiGet(ids.stream().map(TicketHoldService::holdKey).toList()));
        Map<Long, Hold> holds = new HashMap<>();
        for (int i = 0; values != null && i < ids.size(); i++) {
            Hold hold = parse(values.get(i));
            if (hold != null) {
                holds.put(ids.get(i), hold);
            }
        }
        return holds;
    }

    /** Ids of an event's tickets that are currently held. */
    public Set<Long> heldTicketIds(Long eventId) {
        return liveMembers(eventIndexKey(eventId));
    }

    /** Ids of the tickets a user currently holds. */
    public Set<Long> heldTicketIdsForUser(Long userId) {
        return liveMembers(userIndexKey(userId));
    }

    @SuppressWarnings("unchecked")
    private Set<Long> liveMembers(String indexKey) {
        List<Object> members = redis(() -> redisTemplate.execute(LIVE_MEMBERS_SCRIPT, List.of(indexKey)));
        Set<Long> ids = new LinkedHashSet<>();
        if (members != null) {
            members.forEach(m -> ids.add(Long.valueOf(m.toString())));
        }
        return ids;
    }

    /**
     * Runs a Redis call, converting any Redis error (connection refused, command timeout, script
     * error) into ReservationsUnavailableException (→ 503), so reservations fail closed with a
     * clear message. A dedicated exception, rather than mapping Spring's DataAccessException
     * subtypes globally: QueryTimeoutException, for one, also comes from Postgres.
     */
    private static <T> T redis(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("Hold store (Redis) unavailable: {}", e.getMessage());
            throw new com.eventmanager.exception.ReservationsUnavailableException(e);
        }
    }

    /** "<userId>:<expiresAtMillis>" → Hold; null for a missing or malformed value. */
    static Hold parse(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new Hold(Long.valueOf(value.substring(0, colon)),
                    Instant.ofEpochMilli(Long.parseLong(value.substring(colon + 1))));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
