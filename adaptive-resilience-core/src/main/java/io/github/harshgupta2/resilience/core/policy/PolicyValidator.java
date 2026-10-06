package io.github.harshgupta2.resilience.core.policy;

import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a {@link RegionPolicy} is consistent before it is accepted.
 *
 * <p>The guard relies on these checks: it assumes every range starts at 1 or more, that the static
 * default is itself safe, and that the DB budget can hold the policy's largest pod count. A policy that
 * fails here must be rejected, never partially applied.
 */
public final class PolicyValidator {

    public ValidationResult validate(RegionPolicy policy) {
        List<String> errors = new ArrayList<>();

        requireNotBlank(policy.region(), "region", errors);
        requireNotBlank(policy.service(), "service", errors);

        // Scale-to-zero, an empty pool or a zero concurrency limit would stop the service, so the
        // guard never plans for them.
        requireMinAtLeastOne(policy.replicas(), "replicas", errors);
        requireMinAtLeastOne(policy.poolSizePerPod(), "poolSizePerPod", errors);
        requireMinAtLeastOne(policy.concurrencyLimit(), "concurrencyLimit", errors);

        if (policy.dbConnectionBudget() < 1) {
            errors.add("dbConnectionBudget must be >= 1, was " + policy.dbConnectionBudget());
        }
        if (policy.podMemoryLimitMb() < 1) {
            errors.add("podMemoryLimitMb must be >= 1, was " + policy.podMemoryLimitMb());
        }
        // Written as "not inside" so NaN is rejected too.
        if (!(policy.memorySafetyFactor() > 0 && policy.memorySafetyFactor() <= 1)) {
            errors.add("memorySafetyFactor must be in (0, 1], was " + policy.memorySafetyFactor());
        }
        if (!(policy.maxStepPercent() > 0 && policy.maxStepPercent() <= 1)) {
            errors.add("maxStepPercent must be in (0, 1], was " + policy.maxStepPercent());
        }
        if (policy.cooldown().isNegative()) {
            errors.add("cooldown must not be negative, was " + policy.cooldown());
        }

        validateStaticDefault(policy, errors);

        // At the largest allowed pod count, even the smallest pool must fit the DB budget. Otherwise
        // the policy allows pod counts that the guard would always have to cut back.
        long connectionsAtMaxReplicas = (long) policy.replicas().max() * policy.poolSizePerPod().min();
        if (connectionsAtMaxReplicas > policy.dbConnectionBudget()) {
            errors.add("replicas.max x poolSizePerPod.min (" + connectionsAtMaxReplicas
                    + ") must be <= dbConnectionBudget (" + policy.dbConnectionBudget() + ")");
        }

        if (!(policy.slo().latencyMsP95() > 0)) {
            errors.add("slo.latencyMsP95 must be > 0, was " + policy.slo().latencyMsP95());
        }
        if (!(policy.slo().errorRate() >= 0 && policy.slo().errorRate() <= 1)) {
            errors.add("slo.errorRate must be in [0, 1], was " + policy.slo().errorRate());
        }

        return new ValidationResult(errors);
    }

    /** The static default is the fallback when anything fails, so it must satisfy the policy on its own. */
    private static void validateStaticDefault(RegionPolicy policy, List<String> errors) {
        TargetConfig fallback = policy.staticDefault();
        requireInRange(fallback.replicas(), policy.replicas(), "staticDefault.replicas", errors);
        requireInRange(fallback.poolSizePerPod(), policy.poolSizePerPod(), "staticDefault.poolSizePerPod", errors);
        requireInRange(fallback.concurrencyLimit(), policy.concurrencyLimit(), "staticDefault.concurrencyLimit", errors);
        if (fallback.totalConnections() > policy.dbConnectionBudget()) {
            errors.add("staticDefault uses " + fallback.totalConnections()
                    + " connections, more than dbConnectionBudget (" + policy.dbConnectionBudget() + ")");
        }
    }

    private static void requireNotBlank(String value, String field, List<String> errors) {
        if (value.isBlank()) {
            errors.add(field + " must not be blank");
        }
    }

    private static void requireMinAtLeastOne(Range range, String field, List<String> errors) {
        if (range.min() < 1) {
            errors.add(field + ".min must be >= 1, was " + range.min());
        }
    }

    private static void requireInRange(int value, Range range, String field, List<String> errors) {
        if (!range.contains(value)) {
            errors.add(field + " (" + value + ") must be within [" + range.min() + ", " + range.max() + "]");
        }
    }
}
