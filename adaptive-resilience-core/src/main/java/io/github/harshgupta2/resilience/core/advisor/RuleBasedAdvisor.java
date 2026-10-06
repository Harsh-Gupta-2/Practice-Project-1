package io.github.harshgupta2.resilience.core.advisor;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic baseline advisor. Always available, needs no AI, and is the fallback when the LLM is
 * off, slow or wrong.
 *
 * <p>It answers one question per decision: <em>where is the waiting happening?</em> The first matching
 * case wins:
 * <ol>
 *   <li>Threads wait for connections and the DB is fine: the pool is the bottleneck, so grow the pool.</li>
 *   <li>Threads wait for connections and the DB is saturated or unknown: more pods or connections would
 *       only push the DB harder, so lower the concurrency limit to shed load instead.</li>
 *   <li>SLO breached without connection waits and the DB is fine: the pods are the bottleneck, so add a
 *       replica.</li>
 *   <li>SLO breached without connection waits and the DB is saturated or unknown: shed load.</li>
 *   <li>Healthy and the pool is mostly idle: shrink the pool to free DB connections.</li>
 *   <li>Otherwise: keep the current target.</li>
 * </ol>
 *
 * <p>Each change is one step of the policy's {@code maxStepPercent} (at least 1). The guard still checks
 * every suggestion. Scaling replicas down is deliberately left to the platform's autoscaler for now.
 */
public final class RuleBasedAdvisor implements Advisor {

    /**
     * Thresholds the rules need. These are starting assumptions, not measured values; they are meant to
     * be tuned from load tests and replay results.
     *
     * @param acquireTimeHighMs  p95 connection acquire time above which threads count as waiting
     * @param lowPoolUtilization fraction of the pool in use below which the pool counts as mostly idle
     */
    public record Settings(double acquireTimeHighMs, double lowPoolUtilization) {

        /** Starting assumption: 50 ms acquire time, 30% pool use. */
        public static Settings defaults() {
            return new Settings(50, 0.3);
        }
    }

    private final Settings settings;

    public RuleBasedAdvisor() {
        this(Settings.defaults());
    }

    public RuleBasedAdvisor(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Suggestion suggest(RegionSignals signals, RegionPolicy policy, TargetConfig current) {
        double step = policy.maxStepPercent();
        boolean dbHasRoom = signals.dbHealth() == DbHealth.OK;
        boolean connectionWait = signals.avgHikariPending() > 0
                || signals.acquireTimeMsP95() > settings.acquireTimeHighMs();
        boolean sloBreached = signals.latencyMsP95() > policy.slo().latencyMsP95()
                || signals.errorRate() > policy.slo().errorRate();
        String db = "DB " + signals.dbHealth();

        if (connectionWait && dbHasRoom) {
            int pool = up(current.poolSizePerPod(), step);
            return suggest(current.withPoolSizePerPod(pool), "Threads wait for connections (pending "
                    + fmt(signals.avgHikariPending()) + ", acquire p95 " + fmt(signals.acquireTimeMsP95())
                    + " ms) and " + db + ": poolSizePerPod " + current.poolSizePerPod() + "->" + pool);
        }
        if (connectionWait) {
            int limit = down(current.concurrencyLimit(), step);
            return suggest(current.withConcurrencyLimit(limit), "Threads wait for connections but " + db
                    + ": more pods or connections would load the DB further, concurrencyLimit "
                    + current.concurrencyLimit() + "->" + limit);
        }
        if (sloBreached && dbHasRoom) {
            int replicas = up(current.replicas(), step);
            return suggest(current.withReplicas(replicas), "SLO breached (latency p95 "
                    + fmt(signals.latencyMsP95()) + " ms, errors " + fmt(signals.errorRate())
                    + ") without connection waits and " + db + ": replicas " + current.replicas() + "->" + replicas);
        }
        if (sloBreached) {
            int limit = down(current.concurrencyLimit(), step);
            return suggest(current.withConcurrencyLimit(limit), "SLO breached and " + db
                    + ": shedding load, concurrencyLimit " + current.concurrencyLimit() + "->" + limit);
        }
        double utilization = signals.avgHikariActive() / current.poolSizePerPod();
        if (utilization < settings.lowPoolUtilization()) {
            int pool = down(current.poolSizePerPod(), step);
            return suggest(current.withPoolSizePerPod(pool), "Healthy and pool mostly idle ("
                    + fmt(utilization * 100) + "% in use): poolSizePerPod " + current.poolSizePerPod() + "->" + pool);
        }
        return suggest(current, "Healthy: keeping current target");
    }

    private static Suggestion suggest(TargetConfig target, String reason) {
        return new Suggestion(target, List.of(reason));
    }

    private static int stepSize(int value, double step) {
        return (int) Math.max(1, Math.ceil(value * step - 1e-9));
    }

    private static int up(int value, double step) {
        return value + stepSize(value, step);
    }

    private static int down(int value, double step) {
        return value - stepSize(value, step);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
