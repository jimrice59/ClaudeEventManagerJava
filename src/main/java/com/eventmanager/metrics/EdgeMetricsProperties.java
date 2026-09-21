package com.eventmanager.metrics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code edge-metrics.enabled} (overridable via the {@code EDGE_METRICS_ENABLED} env
 * var; default {@code true}). Gates the HTTP-edge metrics {@link EdgeMetricsConfig} suppresses —
 * see its javadoc for exactly which meters that covers and why.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edge-metrics")
public class EdgeMetricsProperties {

    private boolean enabled = true;
}
