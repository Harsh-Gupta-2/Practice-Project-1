package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.ArrayList;
import java.util.List;

/** Moves every value into the min-max range of the policy. Hard rule: nothing after it widens a range. */
public final class ClampRule implements GuardRule {

    @Override
    public GuardStep apply(TargetConfig proposed, GuardContext context) {
        RegionPolicy policy = context.policy();
        List<String> changes = new ArrayList<>();

        int replicas = clamp("replicas", proposed.replicas(), policy.replicas(), changes);
        int pool = clamp("poolSizePerPod", proposed.poolSizePerPod(), policy.poolSizePerPod(), changes);
        int concurrency = clamp("concurrencyLimit", proposed.concurrencyLimit(), policy.concurrencyLimit(), changes);

        if (changes.isEmpty()) {
            return GuardStep.unchanged(proposed);
        }
        return GuardStep.adjusted(new TargetConfig(replicas, pool, concurrency),
                "ClampRule: " + String.join(", ", changes));
    }

    private static int clamp(String field, int value, Range range, List<String> changes) {
        int clamped = range.clamp(value);
        if (clamped != value) {
            changes.add(field + " " + value + "->" + clamped
                    + " (range [" + range.min() + ", " + range.max() + "])");
        }
        return clamped;
    }
}
