package io.github.harshgupta2.resilience.core.domain;

/**
 * Service level objective for a service in a region.
 *
 * @param latencyMsP95 target p95 latency in milliseconds
 * @param errorRate    target error rate as a fraction between 0 and 1 (0.01 means 1%)
 */
public record Slo(double latencyMsP95, double errorRate) {
}
