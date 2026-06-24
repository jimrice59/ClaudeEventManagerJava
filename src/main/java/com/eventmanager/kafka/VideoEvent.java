package com.eventmanager.kafka;

public record VideoEvent(String operation, Long performerId, Long videoId) {
}
