package com.eventmanager.client.dto;

/** Mirrors the server's {@code EventStatus}. */
public enum EventStatus {
    AVAILABLE,
    /** Set at the start of an event delete. */
    DELETING
}
