package io.github.harshgupta2.resilience.core.advisor;

import io.github.harshgupta2.resilience.core.TestPolicies;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;
import io.github.harshgupta2.resilience.core.guard.GuardContext;
import io.github.harshgupta2.resilience.core.guard.GuardOutcome;
import io.github.harshgupta2.resilience.core.guard.GuardService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAdvisorTest {

    /** SLO from the plan example: p95 500 ms, 1% errors; step 20%. */
    private static final RegionPolicy POLICY = TestPolicies.valid().build();
    private static final TargetConfig CURRENT = new TargetConfig(4, 10, 80);

    private final RuleBasedAdvisor advisor = new RuleBasedAdvisor();

    private static RegionSignals signals(double active, double pending, double acquireMs, double latencyMs,
                                         double errors, DbHealth db) {
        return new RegionSignals(active, pending, acquireMs, latencyMs, errors, db);
    }

    @Test
    void growsPoolWhenThreadsWaitForConnectionsAndDbHasRoom() {
        Suggestion suggestion = advisor.suggest(signals(10, 25, 850, 1200, 0.02, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target()).isEqualTo(new TargetConfig(4, 12, 80));
        assertThat(suggestion.reasons()).containsExactly("Threads wait for connections (pending 25.0,"
                + " acquire p95 850.0 ms) and DB OK: poolSizePerPod 10->12");
    }

    @Test
    void slowAcquireAloneCountsAsWaiting() {
        Suggestion suggestion = advisor.suggest(signals(10, 0, 51, 100, 0, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target().poolSizePerPod()).isEqualTo(12);
    }

    @Test
    void shedsLoadInsteadOfScalingWhenDbIsSaturated() {
        // The core lesson of the project: waiting on a saturated DB must not add pods or connections.
        Suggestion suggestion = advisor.suggest(signals(10, 25, 850, 1200, 0.02, DbHealth.SATURATED), POLICY, CURRENT);

        assertThat(suggestion.target()).isEqualTo(new TargetConfig(4, 10, 64));
    }

    @Test
    void treatsUnknownDbLikeSaturatedForAnythingThatAddsLoad() {
        Suggestion waiting = advisor.suggest(signals(10, 25, 850, 1200, 0.02, DbHealth.UNKNOWN), POLICY, CURRENT);
        Suggestion slow = advisor.suggest(signals(10, 0, 5, 900, 0, DbHealth.UNKNOWN), POLICY, CURRENT);

        assertThat(waiting.target()).isEqualTo(new TargetConfig(4, 10, 64));
        assertThat(slow.target()).isEqualTo(new TargetConfig(4, 10, 64));
    }

    @Test
    void addsReplicaWhenSloIsBreachedWithoutConnectionWaits() {
        Suggestion suggestion = advisor.suggest(signals(6, 0, 5, 900, 0.001, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target()).isEqualTo(new TargetConfig(5, 10, 80));
    }

    @Test
    void errorRateAloneCountsAsSloBreach() {
        Suggestion suggestion = advisor.suggest(signals(6, 0, 5, 100, 0.05, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target().replicas()).isEqualTo(5);
    }

    @Test
    void shrinksMostlyIdlePoolWhenHealthy() {
        // 2 of 10 connections in use = 20% < 30%
        Suggestion suggestion = advisor.suggest(signals(2, 0, 1, 100, 0, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target()).isEqualTo(new TargetConfig(4, 8, 80));
        assertThat(suggestion.reasons()).containsExactly(
                "Healthy and pool mostly idle (20.0% in use): poolSizePerPod 10->8");
    }

    @Test
    void keepsCurrentTargetWhenHealthyAndBusy() {
        Suggestion suggestion = advisor.suggest(signals(6, 0, 1, 100, 0, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target()).isEqualTo(CURRENT);
        assertThat(suggestion.reasons()).containsExactly("Healthy: keeping current target");
    }

    @Test
    void usesCustomThresholds() {
        RuleBasedAdvisor strict = new RuleBasedAdvisor(new RuleBasedAdvisor.Settings(10, 0.3));

        Suggestion suggestion = strict.suggest(signals(6, 0, 20, 100, 0, DbHealth.OK), POLICY, CURRENT);

        assertThat(suggestion.target().poolSizePerPod()).isEqualTo(12);
    }

    @Test
    void suggestionStillGoesThroughTheGuard() {
        // Pool already at the top of its range: the advisor asks for more, the guard keeps it in range.
        TargetConfig atMax = new TargetConfig(4, 20, 80);
        Suggestion suggestion = advisor.suggest(signals(20, 30, 900, 1200, 0.02, DbHealth.OK), POLICY, atMax);
        GuardContext context = new GuardContext(POLICY, atMax, Instant.parse("2026-10-06T10:00:00Z"),
                Optional.empty(), false, Optional.empty());

        GuardOutcome outcome = new GuardService().evaluate(suggestion.target(), context);

        assertThat(suggestion.target().poolSizePerPod()).isEqualTo(24);
        assertThat(outcome.target().poolSizePerPod()).isEqualTo(20);
    }
}
