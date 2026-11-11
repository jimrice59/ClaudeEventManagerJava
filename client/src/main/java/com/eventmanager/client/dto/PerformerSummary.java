package com.eventmanager.client.dto;

/** Lightweight performer reference embedded in {@link TicketResponse} (id + name only). */
public record PerformerSummary(Long id, String name) {
}
