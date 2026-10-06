package io.github.harshgupta2.resilience.core.domain;

/**
 * The three values the system decides for a service in a region.
 *
 * @param replicas         number of pods
 * @param poolSizePerPod   maximum database connection pool size in each pod
 * @param concurrencyLimit maximum in-flight requests each pod accepts
 */
public record TargetConfig(int replicas, int poolSizePerPod, int concurrencyLimit) {

    /** Total database connections this target can open across all pods. */
    public long totalConnections() {
        return (long) replicas * poolSizePerPod;
    }

    public TargetConfig withReplicas(int newReplicas) {
        return new TargetConfig(newReplicas, poolSizePerPod, concurrencyLimit);
    }

    public TargetConfig withPoolSizePerPod(int newPoolSizePerPod) {
        return new TargetConfig(replicas, newPoolSizePerPod, concurrencyLimit);
    }

    public TargetConfig withConcurrencyLimit(int newConcurrencyLimit) {
        return new TargetConfig(replicas, poolSizePerPod, newConcurrencyLimit);
    }
}
