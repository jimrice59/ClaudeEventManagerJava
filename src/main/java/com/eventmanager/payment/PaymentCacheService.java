package com.eventmanager.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Two short-lived Redis caches around a purchase's {@code paymentRequestId}, independent of (and
 * faster than) the Postgres rows {@code TicketService} also writes — see
 * docs/payment-service-integration.md.
 * <p>
 * Uses {@link StringRedisTemplate} directly (opaque string values via {@code SET ... EX}), the
 * same way {@code RateLimiter} talks to Redis directly rather than through the Spring Cache
 * abstraction's {@code @Cacheable} — these are ad-hoc writes tied to a specific key lifecycle,
 * not a cache-aside read path.
 */
@Component
@RequiredArgsConstructor
public class PaymentCacheService {

    private static final String REQUEST_KEY_PREFIX = "payment:request:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "payment:idempotency-token:";

    private static final Duration REQUEST_TTL = Duration.ofHours(24);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /**
     * Records that {@code paymentRequestId} belongs to {@code ticketId}, cached for 24 hours —
     * written before the gRPC call goes out, so a reconciliation sweep can map a stuck
     * {@code paymentRequestId} (found on the {@code Ticket} row or in a {@code TicketOperation})
     * back to its ticket without a Postgres round-trip, and so the id is resolvable even in the
     * window after the call but before either saga transaction commits.
     */
    public void cachePaymentRequest(String paymentRequestId, Long ticketId) {
        redisTemplate.opsForValue().set(REQUEST_KEY_PREFIX + paymentRequestId, String.valueOf(ticketId), REQUEST_TTL);
    }

    public Optional<Long> findTicketIdForPaymentRequest(String paymentRequestId) {
        String value = redisTemplate.opsForValue().get(REQUEST_KEY_PREFIX + paymentRequestId);
        return Optional.ofNullable(value).map(Long::valueOf);
    }

    /**
     * Caches the payment service's own idempotency token for 5 minutes — a short recovery window
     * that lets the completion transaction recover an APPROVED/DECLINED result from Redis instead
     * of re-calling the payment service, if this application crashes between receiving the gRPC
     * response and committing the second (completion) transaction. Deliberately much shorter than
     * {@link #REQUEST_TTL}: this is a crash-recovery aid, not a durable record — the durable copy
     * is the {@code TicketOperation} row written once the completion transaction commits.
     */
    public void cacheIdempotencyToken(String paymentRequestId, String idempotencyToken) {
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(IDEMPOTENCY_KEY_PREFIX + paymentRequestId, idempotencyToken, IDEMPOTENCY_TTL);
    }

    public Optional<String> findIdempotencyToken(String paymentRequestId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + paymentRequestId));
    }
}
