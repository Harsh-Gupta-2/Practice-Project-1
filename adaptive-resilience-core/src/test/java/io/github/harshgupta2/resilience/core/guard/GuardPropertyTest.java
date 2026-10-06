package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.TestPolicies;
import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import io.github.harshgupta2.resilience.core.policy.PolicyValidator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throws thousands of random policies, current targets, suggestions (including garbage such as negative
 * or huge values, like a bad AI answer) and memory profiles at the guard, and checks the promises that
 * must always hold. The seed is fixed so a failure can be reproduced exactly.
 */
class GuardPropertyTest {

    private static final long SEED = 20261006L;
    private static final int CASES = 5_000;

    private final GuardService guard = new GuardService();
    private final PolicyValidator validator = new PolicyValidator();

    @Test
    void finalTargetAlwaysRespectsRangesAndDbBudget() {
        Random random = new Random(SEED);
        int checked = 0;
        while (checked < CASES) {
            RegionPolicy policy = randomPolicy(random);
            if (!validator.validate(policy).isValid()) {
                continue;
            }
            TargetConfig current = randomTarget(random, 0, 300);
            GuardContext context = new GuardContext(policy, current, NOW,
                    random.nextBoolean() ? Optional.empty() : Optional.of(NOW.minusSeconds(random.nextInt(600))),
                    random.nextInt(10) == 0, randomMemory(random));
            TargetConfig suggestion = randomSuggestion(random);

            GuardOutcome outcome = guard.evaluate(suggestion, context);

            TargetConfig result = outcome.target();
            String description = "case " + checked + ": " + policy + " current=" + current
                    + " suggestion=" + suggestion + " -> " + outcome;
            if (outcome.held()) {
                assertThat(result).as(description).isEqualTo(current);
            }
            assertThat(policy.replicas().contains(result.replicas())).as(description).isTrue();
            assertThat(policy.poolSizePerPod().contains(result.poolSizePerPod())).as(description).isTrue();
            assertThat(policy.concurrencyLimit().contains(result.concurrencyLimit())).as(description).isTrue();
            assertThat(result.totalConnections()).as(description).isLessThanOrEqualTo(policy.dbConnectionBudget());
            checked++;
        }
    }

    @Test
    void neverIncreasesPoolBeyondTheSuggestionOrTheCurrentStep() {
        Random random = new Random(SEED + 1);
        int checked = 0;
        while (checked < CASES) {
            RegionPolicy policy = randomPolicy(random);
            if (!validator.validate(policy).isValid()) {
                continue;
            }
            TargetConfig current = policy.staticDefault();
            TargetConfig suggestion = randomTarget(random, 1, 300);
            GuardContext context = new GuardContext(policy, current, NOW, Optional.empty(), false, randomMemory(random));

            TargetConfig result = guard.evaluate(suggestion, context).target();

            // The guard may raise a value only to reach the range minimum, never above what was asked.
            int ceiling = Math.max(suggestion.poolSizePerPod(), policy.poolSizePerPod().min());
            assertThat(result.poolSizePerPod()).isLessThanOrEqualTo(ceiling);
            checked++;
        }
    }

    private static RegionPolicy randomPolicy(Random random) {
        TestPolicies policy = TestPolicies.valid();
        policy.replicas = randomRange(random, 1, 50);
        policy.poolSizePerPod = randomRange(random, 1, 60);
        policy.concurrencyLimit = randomRange(random, 1, 500);
        policy.dbConnectionBudget = 1 + random.nextInt(2_000);
        policy.maxStepPercent = 0.01 + random.nextDouble() * 0.99;
        policy.memorySafetyFactor = 0.5 + random.nextDouble() * 0.5;
        policy.cooldown = Duration.ofSeconds(random.nextInt(600));
        policy.staticDefault = new TargetConfig(policy.replicas.min(), policy.poolSizePerPod.min(),
                policy.concurrencyLimit.min());
        return policy.build();
    }

    private static Range randomRange(Random random, int lowest, int highest) {
        int a = lowest + random.nextInt(highest - lowest + 1);
        int b = lowest + random.nextInt(highest - lowest + 1);
        return new Range(Math.min(a, b), Math.max(a, b));
    }

    private static TargetConfig randomTarget(Random random, int lowest, int highest) {
        return new TargetConfig(lowest + random.nextInt(highest - lowest + 1),
                lowest + random.nextInt(highest - lowest + 1),
                lowest + random.nextInt(highest - lowest + 1));
    }

    /** Mix of sensible values and garbage, as a buggy advisor or a confused LLM might return. */
    private static TargetConfig randomSuggestion(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> new TargetConfig(-random.nextInt(100), -random.nextInt(100), -random.nextInt(100));
            case 1 -> new TargetConfig(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            default -> randomTarget(random, 0, 1_000);
        };
    }

    private static Optional<MemoryProfile> randomMemory(Random random) {
        if (random.nextInt(3) == 0) {
            return Optional.empty();
        }
        return Optional.of(new MemoryProfile(256 + random.nextInt(2_048), random.nextInt(512),
                random.nextInt(500), random.nextDouble() * 2, random.nextDouble() * 30));
    }
}
