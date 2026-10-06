package io.github.harshgupta2.resilience.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Metrics reported by one pod at one moment. See {@code docs/PLAN.md}, sections E and G.
 *
 * @param region            region of the pod
 * @param podId             pod identifier
 * @param at                when the metrics were taken
 * @param hikariActive      connections in use
 * @param hikariIdle        connections open but idle
 * @param hikariPending     threads waiting for a connection
 * @param acquireTimeMsP95  p95 time to get a connection, in milliseconds
 * @param executorQueueSize tasks waiting in the request executor
 * @param blockedThreads    threads in {@code BLOCKED} state (sampled)
 * @param waitingThreads    threads in {@code WAITING} or {@code TIMED_WAITING} state (sampled)
 * @param latencyMsP95      p95 request latency, in milliseconds
 * @param errorRate         errors as a fraction of requests, between 0 and 1
 * @param rps               requests per second
 * @param heapUsedMb        heap in use, in MB
 * @param oldGenAfterGcMb   old generation size after the last GC, in MB
 * @param nonHeapMb         non-heap memory (metaspace, code cache, ...), in MB
 * @param threadCount       live threads
 */
public record MetricSnapshot(
        String region,
        String podId,
        Instant at,
        int hikariActive,
        int hikariIdle,
        int hikariPending,
        double acquireTimeMsP95,
        int executorQueueSize,
        int blockedThreads,
        int waitingThreads,
        double latencyMsP95,
        double errorRate,
        double rps,
        long heapUsedMb,
        long oldGenAfterGcMb,
        long nonHeapMb,
        int threadCount) {

    public MetricSnapshot {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(podId, "podId");
        Objects.requireNonNull(at, "at");
    }
}
