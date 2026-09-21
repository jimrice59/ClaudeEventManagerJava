package com.eventmanager.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Suppresses edge-level HTTP metrics entirely — via a Micrometer {@link MeterFilter}, the same
 * extension point Spring Boot's own {@code management.metrics.enable.*} property uses internally
 * — when {@link EdgeMetricsProperties#isEnabled()} is {@code false}. A denied meter is never
 * registered at all, so it disappears from {@code /actuator/prometheus} (and stops paying its
 * small per-request instrumentation cost) rather than merely being hidden from one exporter.
 * <p>
 * {@code http.server.requests} alone carries three of the four metrics this gates: request rate
 * (its count), latency percentiles (its distribution — see the {@code management.metrics
 * .distribution.*} config in application.yml, which configures percentile computation for this
 * one meter unconditionally, since it's a no-op once the meter itself is denied), and
 * status-code breakdown per route (its {@code uri}/{@code status}/{@code method}/{@code outcome}
 * tags, added automatically by Spring's {@code WebMvcTagsProvider}). {@code process.uptime} /
 * {@code process.start.time} are the fourth (uptime). {@code http.server.requests.active} — a
 * separate {@code LongTaskTimer} tracking in-flight request concurrency, distinct from the
 * completed-request {@code http.server.requests} timer above — is included alongside it since
 * it's the same edge-traffic concept (confirmed present in {@code /actuator/prometheus} output
 * even after denying {@code http.server.requests} during manual verification).
 * <p>
 * Deliberately NOT included: {@code jvm.*} (heap, GC, threads), {@code process.cpu.usage},
 * {@code process.files.*}, and the Hikari connection-pool metrics — those describe this
 * process's own internal resource usage, have no gateway equivalent, and must stay regardless of
 * whether an API gateway is also reporting edge traffic (see CLAUDE.md "Monitoring").
 */
@Configuration
@RequiredArgsConstructor
public class EdgeMetricsConfig {

    private static final Set<String> EDGE_METER_NAMES = Set.of(
            "http.server.requests", "http.server.requests.active", "process.uptime", "process.start.time");

    private final EdgeMetricsProperties properties;

    @Bean
    public MeterFilter edgeMetricsFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (!properties.isEnabled() && EDGE_METER_NAMES.contains(id.getName())) {
                    return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }
}
