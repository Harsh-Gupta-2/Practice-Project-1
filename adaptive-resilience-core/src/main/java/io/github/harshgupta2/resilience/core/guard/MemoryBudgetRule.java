package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.Locale;

/**
 * Keeps the memory one pod needs within {@code podMemoryLimitMb x memorySafetyFactor}:
 * {@code heapMax + nonHeap + threads x stack + pool x perConnectionOverhead}.
 *
 * <p>Only the pool can be changed here, and never below its minimum. If the fixed part (heap, non-heap,
 * threads) alone does not fit, no pool size can fix it; the rule then uses the minimum pool and reports
 * it so a human can fix the pod size or JVM settings.
 *
 * <p>Without a {@link MemoryProfile} the rule cannot check anything and says so in the guard steps
 * instead of passing silently.
 */
public final class MemoryBudgetRule implements GuardRule {

    @Override
    public GuardStep apply(TargetConfig proposed, GuardContext context) {
        if (context.memoryProfile().isEmpty()) {
            return GuardStep.adjusted(proposed, "MemoryBudgetRule: skipped, no memory profile available");
        }
        MemoryProfile memory = context.memoryProfile().get();
        RegionPolicy policy = context.policy();
        double allowedMb = policy.podMemoryLimitMb() * policy.memorySafetyFactor();

        if (memory.requiredMb(proposed.poolSizePerPod()) <= allowedMb) {
            return GuardStep.unchanged(proposed);
        }

        int minPool = policy.poolSizePerPod().min();
        int pool = minPool;
        if (memory.perConnectionOverheadMb() > 0) {
            int fitting = (int) Math.floor((allowedMb - memory.fixedMb()) / memory.perConnectionOverheadMb());
            pool = Math.max(minPool, Math.min(proposed.poolSizePerPod(), fitting));
        }

        String reason = "MemoryBudgetRule: poolSizePerPod " + proposed.poolSizePerPod() + "->" + pool
                + " (needs " + mb(memory.requiredMb(proposed.poolSizePerPod())) + " MB, allowed "
                + mb(allowedMb) + " MB)";
        if (memory.requiredMb(pool) > allowedMb) {
            reason += "; WARNING: still needs " + mb(memory.requiredMb(pool))
                    + " MB at the minimum pool, pod memory or JVM settings must be fixed by a human";
        }
        return GuardStep.adjusted(proposed.withPoolSizePerPod(pool), reason);
    }

    private static String mb(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
