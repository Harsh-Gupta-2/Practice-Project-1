package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Limits how far each value may move away from the current target in one decision, so the system
 * changes gradually and does not oscillate.
 *
 * <p>The allowed change is {@code ceil(current x maxStepPercent)}, but at least 1. Without the "at
 * least 1", a small value (for example 2 replicas at 20%) could never change at all.
 *
 * <p>This is a soft rule: {@link ClampRule} and the budget rules run after it and always win.
 */
public final class MaxStepRule implements GuardRule {

    @Override
    public GuardStep apply(TargetConfig proposed, GuardContext context) {
        TargetConfig current = context.current();
        double maxStep = context.policy().maxStepPercent();
        List<String> changes = new ArrayList<>();

        int replicas = limit("replicas", current.replicas(), proposed.replicas(), maxStep, changes);
        int pool = limit("poolSizePerPod", current.poolSizePerPod(), proposed.poolSizePerPod(), maxStep, changes);
        int concurrency = limit("concurrencyLimit", current.concurrencyLimit(), proposed.concurrencyLimit(),
                maxStep, changes);

        if (changes.isEmpty()) {
            return GuardStep.unchanged(proposed);
        }
        return GuardStep.adjusted(new TargetConfig(replicas, pool, concurrency),
                "MaxStepRule: " + String.join(", ", changes)
                        + " (max " + Math.round(maxStep * 100) + "% step from current)");
    }

    private static int limit(String field, int current, int proposed, double maxStep, List<String> changes) {
        // The small epsilon stops floating-point noise (for example 100 x 0.07 = 7.000000000000001)
        // from rounding the allowed step up by one.
        long allowed = Math.max(1L, (long) Math.ceil(Math.abs((double) current) * maxStep - 1e-9));
        long low = current - allowed;
        long high = current + allowed;
        int limited = (int) Math.max(Integer.MIN_VALUE, Math.max(low, Math.min(high, proposed)));
        if (limited != proposed) {
            changes.add(field + " " + proposed + "->" + limited);
        }
        return limited;
    }
}
