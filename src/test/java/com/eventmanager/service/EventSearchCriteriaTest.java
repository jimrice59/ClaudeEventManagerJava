package com.eventmanager.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSearchCriteriaTest {

    private static final LocalDateTime JUNE_1 = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime JUNE_30 = LocalDateTime.of(2026, 6, 30, 23, 59);

    @Test
    void rejectsStartAfterEnd() {
        assertThatThrownBy(() -> new EventSearchCriteria(null, null, JUNE_30, JUNE_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void allowsEqualOrOneSidedBounds() {
        assertThat(new EventSearchCriteria(null, null, JUNE_1, JUNE_1).hasDateBound()).isTrue();
        assertThat(new EventSearchCriteria(null, null, JUNE_1, null).hasDateBound()).isTrue();
        assertThat(new EventSearchCriteria(null, null, null, JUNE_30).hasDateBound()).isTrue();
    }

    @Test
    void venueAndPerformerOnlyCriteriaAreCacheableWithDistinctKeys() {
        EventSearchCriteria venueOnly = new EventSearchCriteria(1L, null, null, null);
        EventSearchCriteria performerOnly = new EventSearchCriteria(null, 1L, null, null);

        assertThat(venueOnly.hasDateBound()).isFalse();
        assertThat(venueOnly.cacheKey()).isNotEqualTo(performerOnly.cacheKey());
        assertThat(EventSearchCriteria.none()).isEqualTo(new EventSearchCriteria(null, null, null, null));
    }
}
