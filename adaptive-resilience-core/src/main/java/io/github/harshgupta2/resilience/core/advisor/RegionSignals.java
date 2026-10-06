package io.github.harshgupta2.resilience.core.advisor;

import java.util.Objects;

/**
 * Aggregated metrics for one service in one region over a time window, the input to an {@link Advisor}.
 * Built from {@link io.github.harshgupta2.resilience.core.domain.MetricSnapshot}s by the signal analyzer
 * (a later PR).
 *
 * @param avgHikariActive    average connections in use per pod
 * @param avgHikariPending   average threads waiting for a connection per pod
 * @param acquireTimeMsP95   p95 time to get a connection, in milliseconds
 * @param latencyMsP95       p95 request latency, in milliseconds
 * @param errorRate          errors as a fraction of requests, between 0 and 1
 * @param dbHealth           what we know about the database
 */
public record RegionSignals(
        double avgHikariActive,
        double avgHikariPending,
        double acquireTimeMsP95,
        double latencyMsP95,
        double errorRate,
        DbHealth dbHealth) {

    public RegionSignals {
        Objects.requireNonNull(dbHealth, "dbHealth");
    }
}
