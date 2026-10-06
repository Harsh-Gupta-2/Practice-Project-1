package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of one {@link GuardRule}.
 *
 * @param target the target after this rule
 * @param reason why the rule changed or flagged something; empty when the rule had nothing to say
 */
public record GuardStep(TargetConfig target, Optional<String> reason) {

    public GuardStep {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
    }

    public static GuardStep unchanged(TargetConfig target) {
        return new GuardStep(target, Optional.empty());
    }

    public static GuardStep adjusted(TargetConfig target, String reason) {
        return new GuardStep(target, Optional.of(reason));
    }
}
