package com.eventmanager.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeMetricsConfigTest {

    private static Meter.Id id(String name) {
        return new Meter.Id(name, Tags.empty(), null, null, Meter.Type.TIMER);
    }

    @Test
    void deniesEdgeMetersWhenDisabled() {
        EdgeMetricsProperties properties = new EdgeMetricsProperties();
        properties.setEnabled(false);
        MeterFilter filter = new EdgeMetricsConfig(properties).edgeMetricsFilter();

        assertThat(filter.accept(id("http.server.requests"))).isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(id("http.server.requests.active"))).isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(id("process.uptime"))).isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(id("process.start.time"))).isEqualTo(MeterFilterReply.DENY);
    }

    @Test
    void allowsEdgeMetersWhenEnabled() {
        EdgeMetricsProperties properties = new EdgeMetricsProperties();
        properties.setEnabled(true);
        MeterFilter filter = new EdgeMetricsConfig(properties).edgeMetricsFilter();

        assertThat(filter.accept(id("http.server.requests"))).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(id("http.server.requests.active"))).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(id("process.uptime"))).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(id("process.start.time"))).isEqualTo(MeterFilterReply.NEUTRAL);
    }

    @Test
    void neverSuppressesNonEdgeMetersEvenWhenDisabled() {
        EdgeMetricsProperties properties = new EdgeMetricsProperties();
        properties.setEnabled(false);
        MeterFilter filter = new EdgeMetricsConfig(properties).edgeMetricsFilter();

        assertThat(filter.accept(id("jvm.memory.used"))).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(id("process.cpu.usage"))).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(filter.accept(id("hikaricp.connections.active"))).isEqualTo(MeterFilterReply.NEUTRAL);
    }
}
