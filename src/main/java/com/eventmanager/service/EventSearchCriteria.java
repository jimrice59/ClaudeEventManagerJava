package com.eventmanager.service;

import java.time.LocalDateTime;

/**
 * Optional, combinable filters for {@link EventService#searchEvents}. Every field may be null
 * (= no constraint), and every non-null field is ANDed together: e.g. venueId + performerId +
 * start returns that performer's events at that venue from {@code start} onward. Date bounds are
 * inclusive and each one works on its own (start only = from start onward; end only = up to end).
 * Shared by the REST and gRPC transports, so both apply identical semantics.
 *
 * @throws IllegalArgumentException (→ 400) if both bounds are given and {@code start} is after {@code end}
 */
public record EventSearchCriteria(Long venueId, Long performerId, LocalDateTime start, LocalDateTime end) {

    public EventSearchCriteria {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("start (" + start + ") must not be after end (" + end + ")");
        }
    }

    public static EventSearchCriteria none() {
        return new EventSearchCriteria(null, null, null, null);
    }

    /**
     * Date-range searches aren't cached: an unbounded set of possible start/end values would
     * fragment the cache into near-unique, rarely reused entries (the same reason the old
     * getEventsBetween was uncached). Venue/performer combinations are bounded, so they are.
     */
    public boolean hasDateBound() {
        return start != null || end != null;
    }

    /** Cache key for the date-free case — see {@link #hasDateBound()}. */
    public String cacheKey() {
        return "venue=" + venueId + ",performer=" + performerId;
    }
}
