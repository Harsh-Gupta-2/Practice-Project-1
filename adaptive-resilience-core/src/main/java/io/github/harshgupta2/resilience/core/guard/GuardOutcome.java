package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.List;
import java.util.Objects;

/**
 * Final result of the guard.
 *
 * @param target the value that may be applied
 * @param steps  human-readable reason for every rule that changed or flagged something, in order;
 *               stored in the audit log so engineers can see why a suggestion was changed
 * @param held   true when the guard kept the current target because of a freeze or cooldown
 */
public record GuardOutcome(TargetConfig target, List<String> steps, boolean held) {

    public GuardOutcome {
        Objects.requireNonNull(target, "target");
        steps = List.copyOf(steps);
    }
}
