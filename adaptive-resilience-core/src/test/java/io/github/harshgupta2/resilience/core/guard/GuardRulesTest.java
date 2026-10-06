package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.TestPolicies;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.NOW;
import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.POLICY;
import static io.github.harshgupta2.resilience.core.guard.GuardTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;

class GuardRulesTest {

    @Nested
    class MaxStep {

        private final MaxStepRule rule = new MaxStepRule();

        @Test
        void limitsEachValueToTwentyPercentRoundedUp() {
            // current 5 replicas: 20% = 1; pool 10: 2; concurrency 80: 16
            GuardStep step = rule.apply(new TargetConfig(9, 20, 20), context(new TargetConfig(5, 10, 80)));

            assertThat(step.target()).isEqualTo(new TargetConfig(6, 12, 64));
            assertThat(step.reason()).hasValue("MaxStepRule: replicas 9->6, poolSizePerPod 20->12,"
                    + " concurrencyLimit 20->64 (max 20% step from current)");
        }

        @Test
        void allowsAtLeastOneStepForSmallValues() {
            // 2 x 20% = 0.4; without the minimum of 1 the value could never change
            GuardStep step = rule.apply(new TargetConfig(4, 10, 80), context(new TargetConfig(2, 10, 80)));

            assertThat(step.target().replicas()).isEqualTo(3);
        }

        @Test
        void floatingPointNoiseDoesNotWidenTheStep() {
            // 100 x 0.07 is 7.000000000000001 in double; the allowed step must still be 7, not 8
            TestPolicies policy = TestPolicies.valid();
            policy.maxStepPercent = 0.07;

            GuardStep step = rule.apply(new TargetConfig(4, 10, 150),
                    context(policy.build(), new TargetConfig(4, 10, 100)));

            assertThat(step.target().concurrencyLimit()).isEqualTo(107);
        }

        @Test
        void leavesChangesInsideTheStepAlone() {
            TargetConfig proposed = new TargetConfig(5, 11, 90);

            GuardStep step = rule.apply(proposed, context(new TargetConfig(5, 10, 80)));

            assertThat(step).isEqualTo(GuardStep.unchanged(proposed));
        }
    }

    @Nested
    class Clamp {

        private final ClampRule rule = new ClampRule();

        @Test
        void movesValuesIntoTheirRanges() {
            GuardStep step = rule.apply(new TargetConfig(50, -3, 1), context(new TargetConfig(4, 10, 80)));

            assertThat(step.target()).isEqualTo(new TargetConfig(10, 5, 20));
            assertThat(step.reason()).hasValue("ClampRule: replicas 50->10 (range [2, 10]),"
                    + " poolSizePerPod -3->5 (range [5, 20]), concurrencyLimit 1->20 (range [20, 200])");
        }

        @Test
        void leavesValuesInsideTheRangesAlone() {
            TargetConfig proposed = new TargetConfig(2, 20, 200);

            assertThat(rule.apply(proposed, context(proposed))).isEqualTo(GuardStep.unchanged(proposed));
        }
    }

    @Nested
    class DbBudget {

        private final DbBudgetRule rule = new DbBudgetRule();

        @Test
        void reducesPoolFirst() {
            // 8 x 18 = 144 > 120 -> pool 120 / 8 = 15
            GuardStep step = rule.apply(new TargetConfig(8, 18, 80), context(new TargetConfig(4, 10, 80)));

            assertThat(step.target()).isEqualTo(new TargetConfig(8, 15, 80));
            assertThat(step.reason()).hasValue(
                    "DbBudgetRule: poolSizePerPod 18->15 (8 x 18 = 144 > budget 120)");
        }

        @Test
        void acceptsExactlyTheBudget() {
            TargetConfig proposed = new TargetConfig(6, 20, 80); // 120

            assertThat(rule.apply(proposed, context(proposed))).isEqualTo(GuardStep.unchanged(proposed));
        }

        @Test
        void reducesReplicasWhenTheMinimumPoolStillDoesNotFit() {
            // Only reachable when the policy is not validated; the rule is a safety net for that case.
            TestPolicies loose = TestPolicies.valid();
            loose.dbConnectionBudget = 30;
            loose.staticDefault = new TargetConfig(2, 5, 80);
            RegionPolicy policy = loose.build();

            GuardStep step = rule.apply(new TargetConfig(10, 5, 80), context(policy, new TargetConfig(2, 5, 80)));

            assertThat(step.target()).isEqualTo(new TargetConfig(6, 5, 80));
        }
    }

    @Nested
    class MemoryBudget {

        private final MemoryBudgetRule rule = new MemoryBudgetRule();

        // allowed = 2048 x 0.85 = 1740.8 MB; fixed = 1024 + 256 + 200 x 1 = 1480 MB; 20 MB per connection
        private final MemoryProfile memory = new MemoryProfile(1024, 256, 200, 1.0, 20.0);

        private GuardContext withMemory(MemoryProfile profile) {
            return new GuardContext(POLICY, new TargetConfig(4, 10, 80), NOW, Optional.empty(), false,
                    Optional.of(profile));
        }

        @Test
        void reducesPoolToWhatFitsInMemory() {
            // 1480 + 20 x 20 = 1880 > 1740.8; fits: floor(260.8 / 20) = 13
            GuardStep step = rule.apply(new TargetConfig(4, 20, 80), withMemory(memory));

            assertThat(step.target().poolSizePerPod()).isEqualTo(13);
            assertThat(step.reason()).hasValue(
                    "MemoryBudgetRule: poolSizePerPod 20->13 (needs 1880 MB, allowed 1741 MB)");
        }

        @Test
        void leavesPoolAloneWhenItFits() {
            TargetConfig proposed = new TargetConfig(4, 13, 80);

            assertThat(rule.apply(proposed, withMemory(memory))).isEqualTo(GuardStep.unchanged(proposed));
        }

        @Test
        void warnsWhenEvenTheMinimumPoolDoesNotFit() {
            MemoryProfile tooBig = new MemoryProfile(1800, 256, 200, 1.0, 20.0);

            GuardStep step = rule.apply(new TargetConfig(4, 10, 80), withMemory(tooBig));

            assertThat(step.target().poolSizePerPod()).isEqualTo(5);
            assertThat(step.reason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("WARNING: still needs 2356 MB at the minimum pool"));
        }

        @Test
        void saysSoWhenNoMemoryProfileIsAvailable() {
            TargetConfig proposed = new TargetConfig(4, 20, 80);

            GuardStep step = rule.apply(proposed, context(new TargetConfig(4, 10, 80)));

            assertThat(step.target()).isEqualTo(proposed);
            assertThat(step.reason()).hasValue("MemoryBudgetRule: skipped, no memory profile available");
        }
    }
}
