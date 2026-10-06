package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.TargetConfig;

/**
 * One step of the guard. Takes the target proposed so far and returns it unchanged or adjusted.
 *
 * <p>Users can add their own rules (for example "no scale-down in business hours"). Those always run
 * before the built-in safety rules, so they can never push a value outside the policy or the budgets.
 */
@FunctionalInterface
public interface GuardRule {

    GuardStep apply(TargetConfig proposed, GuardContext context);
}
