package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.TestPolicies;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.NOW;
import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.POLICY;
import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardServiceTest {

    private static final TargetConfig CURRENT = new TargetConfig(4, 10, 80);

    private final GuardService guard = new GuardService();

    @Test
    void runsRulesInOrderAndRecordsEachReason() {
        // LLM-style suggestion from the plan example (section G): 8 replicas, pool 18
        GuardOutcome outcome = guard.evaluate(new TargetConfig(8, 18, 60), context(CURRENT));

        // step: replicas 4 +-1 -> 5, pool 10 +-2 -> 12, concurrency 80 +-16 -> 64
        assertThat(outcome.target()).isEqualTo(new TargetConfig(5, 12, 64));
        assertThat(outcome.held()).isFalse();
        assertThat(outcome.steps()).containsExactly(
                "MaxStepRule: replicas 8->5, poolSizePerPod 18->12, concurrencyLimit 60->64"
                        + " (max 20% step from current)",
                "MemoryBudgetRule: skipped, no memory profile available");
    }

    @Test
    void keepsCurrentTargetWhileFrozen() {
        GuardContext frozen = new GuardContext(POLICY, CURRENT, NOW, Optional.empty(), true, Optional.empty());

        GuardOutcome outcome = guard.evaluate(new TargetConfig(10, 20, 200), frozen);

        assertThat(outcome.target()).isEqualTo(CURRENT);
        assertThat(outcome.held()).isTrue();
        assertThat(outcome.steps()).containsExactly("Frozen: keeping current target");
    }

    @Test
    void keepsCurrentTargetDuringCooldown() {
        GuardContext cooling = new GuardContext(POLICY, CURRENT, NOW,
                Optional.of(NOW.minus(Duration.ofSeconds(299))), false, Optional.empty());

        GuardOutcome outcome = guard.evaluate(new TargetConfig(5, 10, 80), cooling);

        assertThat(outcome.target()).isEqualTo(CURRENT);
        assertThat(outcome.held()).isTrue();
    }

    @Test
    void allowsChangesOnceCooldownHasPassed() {
        GuardContext cooled = new GuardContext(POLICY, CURRENT, NOW,
                Optional.of(NOW.minus(Duration.ofSeconds(300))), false, Optional.empty());

        GuardOutcome outcome = guard.evaluate(new TargetConfig(5, 10, 80), cooled);

        assertThat(outcome.target()).isEqualTo(new TargetConfig(5, 10, 80));
        assertThat(outcome.held()).isFalse();
    }

    @Test
    void correctsAnUnsafeCurrentTargetEvenWhenFrozen() {
        // 12 replicas is above the range, for example because the policy was tightened
        TargetConfig unsafe = new TargetConfig(12, 10, 80);
        GuardContext frozen = new GuardContext(POLICY, unsafe, NOW, Optional.empty(), true, Optional.empty());

        GuardOutcome outcome = guard.evaluate(unsafe, frozen);

        assertThat(outcome.held()).isFalse();
        assertThat(outcome.target().replicas()).isEqualTo(10);
        assertThat(outcome.steps().getFirst())
                .isEqualTo("Frozen, but current target breaks the policy: correcting it anyway");
    }

    @Test
    void builtInRulesWinOverExtraRules() {
        GuardRule greedy = (proposed, ctx) -> GuardStep.adjusted(new TargetConfig(1000, 1000, 1000), "Greedy");
        GuardService withExtra = new GuardService(List.of(greedy));
        // Large step so only the hard rules limit the result
        TestPolicies wideStep = TestPolicies.valid();
        wideStep.maxStepPercent = 1.0;

        GuardOutcome outcome = withExtra.evaluate(CURRENT, context(wideStep.build(), new TargetConfig(10, 20, 200)));

        assertThat(outcome.steps().getFirst()).isEqualTo("Greedy");
        assertThat(outcome.target().replicas()).isLessThanOrEqualTo(10);
        assertThat(outcome.target().totalConnections()).isLessThanOrEqualTo(120);
    }

    @Test
    void rejectsAnInvalidPolicy() {
        TestPolicies invalid = TestPolicies.valid();
        invalid.dbConnectionBudget = 10;

        assertThatThrownBy(() -> guard.evaluate(CURRENT, context(invalid.build(), CURRENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is invalid");
    }
}
