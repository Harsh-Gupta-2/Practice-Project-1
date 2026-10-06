package io.github.harshgupta2.resilience.core.policy;

import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.Slo;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyValidatorTest {

    private final PolicyValidator validator = new PolicyValidator();

    @Test
    void acceptsTheExamplePolicyFromThePlan() {
        ValidationResult result = validator.validate(TestPolicies.valid().build());

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsBlankRegionAndService() {
        TestPolicies policy = TestPolicies.valid();
        policy.region = " ";
        policy.service = "";

        assertThat(validator.validate(policy.build()).errors())
                .containsExactly("region must not be blank", "service must not be blank");
    }

    @Test
    void rejectsRangesThatStartAtZero() {
        TestPolicies policy = TestPolicies.valid();
        policy.replicas = new Range(0, 10);
        policy.poolSizePerPod = new Range(0, 20);
        policy.concurrencyLimit = new Range(0, 200);
        policy.staticDefault = new TargetConfig(4, 10, 80);

        assertThat(validator.validate(policy.build()).errors()).contains(
                "replicas.min must be >= 1, was 0",
                "poolSizePerPod.min must be >= 1, was 0",
                "concurrencyLimit.min must be >= 1, was 0");
    }

    @Test
    void rejectsNonPositiveBudgetAndMemory() {
        TestPolicies policy = TestPolicies.valid();
        policy.dbConnectionBudget = 0;
        policy.podMemoryLimitMb = 0;

        assertThat(validator.validate(policy.build()).errors()).contains(
                "dbConnectionBudget must be >= 1, was 0",
                "podMemoryLimitMb must be >= 1, was 0");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, 1.01, Double.NaN})
    void rejectsSafetyFactorOutsideZeroToOne(double factor) {
        TestPolicies policy = TestPolicies.valid();
        policy.memorySafetyFactor = factor;

        assertThat(validator.validate(policy.build()).errors())
                .containsExactly("memorySafetyFactor must be in (0, 1], was " + factor);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.5, Double.NaN})
    void rejectsMaxStepOutsideZeroToOne(double step) {
        TestPolicies policy = TestPolicies.valid();
        policy.maxStepPercent = step;

        assertThat(validator.validate(policy.build()).errors())
                .containsExactly("maxStepPercent must be in (0, 1], was " + step);
    }

    @Test
    void acceptsFullStepAndFullMemory() {
        TestPolicies policy = TestPolicies.valid();
        policy.maxStepPercent = 1.0;
        policy.memorySafetyFactor = 1.0;

        assertThat(validator.validate(policy.build()).isValid()).isTrue();
    }

    @Test
    void rejectsNegativeCooldownButAllowsZero() {
        TestPolicies negative = TestPolicies.valid();
        negative.cooldown = Duration.ofSeconds(-1);
        TestPolicies zero = TestPolicies.valid();
        zero.cooldown = Duration.ZERO;

        assertThat(validator.validate(negative.build()).errors())
                .containsExactly("cooldown must not be negative, was PT-1S");
        assertThat(validator.validate(zero.build()).isValid()).isTrue();
    }

    @Test
    void rejectsStaticDefaultOutsideRanges() {
        TestPolicies policy = TestPolicies.valid();
        policy.staticDefault = new TargetConfig(1, 21, 500);

        assertThat(validator.validate(policy.build()).errors()).containsExactly(
                "staticDefault.replicas (1) must be within [2, 10]",
                "staticDefault.poolSizePerPod (21) must be within [5, 20]",
                "staticDefault.concurrencyLimit (500) must be within [20, 200]");
    }

    @Test
    void rejectsStaticDefaultThatBreaksTheDbBudget() {
        TestPolicies policy = TestPolicies.valid();
        // 10 x 20 = 200 connections, budget is 120. Each value is inside its own range.
        policy.staticDefault = new TargetConfig(10, 20, 80);

        assertThat(validator.validate(policy.build()).errors())
                .containsExactly("staticDefault uses 200 connections, more than dbConnectionBudget (120)");
    }

    @Test
    void rejectsPolicyWhereMaxReplicasCannotFitEvenTheSmallestPool() {
        TestPolicies policy = TestPolicies.valid();
        // 10 x 5 = 50 connections, but the budget is 40.
        policy.dbConnectionBudget = 40;
        policy.staticDefault = new TargetConfig(2, 5, 80);

        assertThat(validator.validate(policy.build()).errors())
                .containsExactly("replicas.max x poolSizePerPod.min (50) must be <= dbConnectionBudget (40)");
    }

    @Test
    void acceptsPolicyWhereMaxReplicasExactlyFitTheBudget() {
        TestPolicies policy = TestPolicies.valid();
        policy.dbConnectionBudget = 50;

        assertThat(validator.validate(policy.build()).isValid()).isTrue();
    }

    @Test
    void rejectsInvalidSlo() {
        TestPolicies policy = TestPolicies.valid();
        policy.slo = new Slo(0, 1.5);

        assertThat(validator.validate(policy.build()).errors()).containsExactly(
                "slo.latencyMsP95 must be > 0, was 0.0",
                "slo.errorRate must be in [0, 1], was 1.5");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        TestPolicies policy = TestPolicies.valid();
        policy.region = "";
        policy.dbConnectionBudget = 0;
        policy.slo = new Slo(-1, 0.01);

        assertThat(validator.validate(policy.build()).errors()).hasSizeGreaterThanOrEqualTo(3);
    }
}
