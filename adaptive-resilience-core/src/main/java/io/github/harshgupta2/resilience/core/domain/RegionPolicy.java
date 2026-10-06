package io.github.harshgupta2.resilience.core.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Limits for one service in one region, agreed by the product owner and engineers.
 *
 * <p>The guard never produces a target outside these limits. Constructing a policy only checks that
 * nothing is {@code null}; whether the numbers make sense together is checked by
 * {@link io.github.harshgupta2.resilience.core.policy.PolicyValidator}, which reports every problem at once.
 *
 * @param region              region name, for example {@code ap-south-1}
 * @param service             service name, for example {@code orders}
 * @param replicas            allowed pod count
 * @param poolSizePerPod      allowed database pool size per pod
 * @param concurrencyLimit    allowed in-flight requests per pod
 * @param dbConnectionBudget  total database connections the service may hold in this region
 * @param podMemoryLimitMb    memory limit of one pod, in MB
 * @param memorySafetyFactor  fraction of the pod memory limit the guard may plan for (for example 0.85)
 * @param maxStepPercent      largest change per decision as a fraction of the current value (0.2 means 20%)
 * @param cooldown            minimum time between two applied changes
 * @param mode                how decisions are used
 * @param staticDefault       fallback target when anything fails
 * @param businessCriticality business importance of the service in this region
 * @param slo                 service level objective
 */
public record RegionPolicy(
        String region,
        String service,
        Range replicas,
        Range poolSizePerPod,
        Range concurrencyLimit,
        int dbConnectionBudget,
        long podMemoryLimitMb,
        double memorySafetyFactor,
        double maxStepPercent,
        Duration cooldown,
        Mode mode,
        TargetConfig staticDefault,
        BusinessCriticality businessCriticality,
        Slo slo) {

    public RegionPolicy {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(replicas, "replicas");
        Objects.requireNonNull(poolSizePerPod, "poolSizePerPod");
        Objects.requireNonNull(concurrencyLimit, "concurrencyLimit");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(staticDefault, "staticDefault");
        Objects.requireNonNull(businessCriticality, "businessCriticality");
        Objects.requireNonNull(slo, "slo");
    }
}
