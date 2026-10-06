package io.github.harshgupta2.resilience.core.guard;

/**
 * Memory figures for one pod, used by {@link MemoryBudgetRule}. See {@code docs/PLAN.md}, section L4.
 *
 * <p>The guard does not invent these numbers. They come from the pod's JVM settings and metrics, and
 * the per-connection overhead is calibrated from observed data in a later phase.
 *
 * @param heapMaxMb               maximum heap ({@code -Xmx}), in MB
 * @param nonHeapMb               metaspace, code cache and other non-heap memory, in MB
 * @param threadCount             live threads
 * @param threadStackMb           stack size per thread, in MB
 * @param perConnectionOverheadMb memory one database connection costs, in MB
 */
public record MemoryProfile(
        long heapMaxMb,
        long nonHeapMb,
        int threadCount,
        double threadStackMb,
        double perConnectionOverheadMb) {

    /** Memory the pod needs no matter how large the pool is. */
    public double fixedMb() {
        return heapMaxMb + nonHeapMb + threadCount * threadStackMb;
    }

    /** Memory the pod needs with a pool of {@code poolSize} connections. */
    public double requiredMb(int poolSize) {
        return fixedMb() + poolSize * perConnectionOverheadMb;
    }
}
