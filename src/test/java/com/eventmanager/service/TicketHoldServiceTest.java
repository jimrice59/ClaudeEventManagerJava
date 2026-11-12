package com.eventmanager.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hold-value parsing and key naming. The Lua scripts themselves need a real Redis (the test
 * profile has none), so they're verified against the docker-compose Redis instead.
 */
class TicketHoldServiceTest {

    @Test
    void parsesUserIdAndExpiryFromTheHoldValue() {
        TicketHoldService.Hold hold = TicketHoldService.parse("42:1794500000000");

        assertThat(hold.userId()).isEqualTo(42L);
        assertThat(hold.expiresAt()).isEqualTo(Instant.ofEpochMilli(1794500000000L));
    }

    @Test
    void missingOrMalformedValuesMeanNoHold() {
        assertThat(TicketHoldService.parse(null)).isNull();
        assertThat(TicketHoldService.parse("")).isNull();
        assertThat(TicketHoldService.parse("no-colon")).isNull();
        assertThat(TicketHoldService.parse("abc:123")).isNull();
        assertThat(TicketHoldService.parse("42:notanumber")).isNull();
    }

    @Test
    void keysAreNamespacedPerTicketEventAndUser() {
        assertThat(TicketHoldService.holdKey(6L)).isEqualTo("hold:ticket:6");
        assertThat(TicketHoldService.eventIndexKey(4L)).isEqualTo("holds:event:4");
        assertThat(TicketHoldService.userIndexKey(42L)).isEqualTo("holds:user:42");
    }
}
