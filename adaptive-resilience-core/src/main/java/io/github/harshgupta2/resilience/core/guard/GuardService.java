package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import io.github.harshgupta2.resilience.core.policy.PolicyValidator;
import io.github.harshgupta2.resilience.core.policy.ValidationResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The deterministic guard (layer L4 in {@code docs/PLAN.md}). Every suggestion, from rules or from AI,
 * passes through here before it can be applied.
 *
 * <p>Order of evaluation:
 * <ol>
 *   <li><b>Hold checks.</b> While frozen or in cooldown the current target is kept, but only if the
 *       current target itself respects the policy. Safety wins over "do not change": a target that breaks
 *       the range or the DB budget is corrected even during a freeze or cooldown.</li>
 *   <li><b>Extra rules</b> supplied by the user, in the given order.</li>
 *   <li><b>Built-in rules</b>, always last and always all of them: {@link MaxStepRule} (soft),
 *       {@link ClampRule}, {@link DbBudgetRule}, {@link MemoryBudgetRule}. Because they run last, no extra
 *       rule or AI suggestion can produce a value outside the range or above the DB budget.</li>
 * </ol>
 *
 * <p>The policy must be valid (see {@link PolicyValidator}); an invalid policy is rejected with an
 * exception, because no safe answer exists for it.
 */
public final class GuardService {

    private final List<GuardRule> extraRules;
    private final List<GuardRule> builtInRules = List.of(
            new MaxStepRule(), new ClampRule(), new DbBudgetRule(), new MemoryBudgetRule());
    private final PolicyValidator policyValidator = new PolicyValidator();

    public GuardService() {
        this(List.of());
    }

    public GuardService(List<GuardRule> extraRules) {
        this.extraRules = List.copyOf(extraRules);
    }

    public GuardOutcome evaluate(TargetConfig proposed, GuardContext context) {
        RegionPolicy policy = context.policy();
        ValidationResult validation = policyValidator.validate(policy);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Policy for " + policy.service() + " in " + policy.region()
                    + " is invalid: " + validation.errors());
        }

        List<String> steps = new ArrayList<>();
        boolean currentRespectsPolicy = respectsPolicy(context.current(), policy);

        if (context.frozen()) {
            if (currentRespectsPolicy) {
                steps.add("Frozen: keeping current target");
                return new GuardOutcome(context.current(), steps, true);
            }
            steps.add("Frozen, but current target breaks the policy: correcting it anyway");
        } else if (inCooldown(context)) {
            if (currentRespectsPolicy) {
                steps.add("Cooldown: last change at " + context.lastAppliedAt().orElseThrow()
                        + ", cooldown " + policy.cooldown() + ", keeping current target");
                return new GuardOutcome(context.current(), steps, true);
            }
            steps.add("In cooldown, but current target breaks the policy: correcting it anyway");
        }

        TargetConfig target = proposed;
        for (GuardRule rule : extraRules) {
            target = applyRule(rule, target, context, steps);
        }
        for (GuardRule rule : builtInRules) {
            target = applyRule(rule, target, context, steps);
        }

        if (!respectsPolicy(target, policy)) {
            // Cannot happen with a valid policy and the built-in rules; failing loudly beats applying it.
            throw new IllegalStateException("Guard produced " + target + " which breaks the policy; steps: " + steps);
        }
        return new GuardOutcome(target, steps, false);
    }

    private static TargetConfig applyRule(GuardRule rule, TargetConfig target, GuardContext context,
                                          List<String> steps) {
        GuardStep step = rule.apply(target, context);
        step.reason().ifPresent(steps::add);
        return step.target();
    }

    private static boolean inCooldown(GuardContext context) {
        Optional<Instant> lastAppliedAt = context.lastAppliedAt();
        return lastAppliedAt.isPresent()
                && context.now().isBefore(lastAppliedAt.get().plus(context.policy().cooldown()));
    }

    /** Hard constraints: every value inside its range and the DB budget respected. */
    static boolean respectsPolicy(TargetConfig target, RegionPolicy policy) {
        return policy.replicas().contains(target.replicas())
                && policy.poolSizePerPod().contains(target.poolSizePerPod())
                && policy.concurrencyLimit().contains(target.concurrencyLimit())
                && target.totalConnections() <= policy.dbConnectionBudget();
    }
}
