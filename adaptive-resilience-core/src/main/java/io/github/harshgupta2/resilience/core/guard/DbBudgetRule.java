package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

/**
 * Keeps {@code replicas x poolSizePerPod} within the region's database connection budget.
 *
 * <p>The pool is reduced first (down to its minimum), because more pods with smaller pools still serve
 * more requests than fewer pods. Only if the minimum pool still does not fit are replicas reduced.
 * With a validated policy ({@code replicas.max x poolSizePerPod.min <= budget}) the second step is never
 * needed; it stays as a safety net.
 *
 * <p>Must run after {@link ClampRule}: it assumes values are already inside their ranges.
 */
public final class DbBudgetRule implements GuardRule {

    @Override
    public GuardStep apply(TargetConfig proposed, GuardContext context) {
        RegionPolicy policy = context.policy();
        int budget = policy.dbConnectionBudget();
        if (proposed.totalConnections() <= budget) {
            return GuardStep.unchanged(proposed);
        }

        int replicas = Math.max(1, proposed.replicas());
        int pool = Math.max(policy.poolSizePerPod().min(), budget / replicas);
        if ((long) replicas * pool <= budget) {
            return GuardStep.adjusted(proposed.withPoolSizePerPod(pool),
                    "DbBudgetRule: poolSizePerPod " + proposed.poolSizePerPod() + "->" + pool
                            + " (" + replicas + " x " + proposed.poolSizePerPod() + " = "
                            + proposed.totalConnections() + " > budget " + budget + ")");
        }

        int reducedReplicas = Math.max(policy.replicas().min(), budget / pool);
        return GuardStep.adjusted(new TargetConfig(reducedReplicas, pool, proposed.concurrencyLimit()),
                "DbBudgetRule: poolSizePerPod " + proposed.poolSizePerPod() + "->" + pool
                        + " and replicas " + proposed.replicas() + "->" + reducedReplicas
                        + " (budget " + budget + ")");
    }
}
