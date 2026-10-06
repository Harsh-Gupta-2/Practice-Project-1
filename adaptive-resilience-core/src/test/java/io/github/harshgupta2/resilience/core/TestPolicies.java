package io.github.harshgupta2.resilience.core;

import io.github.harshgupta2.resilience.core.domain.BusinessCriticality;
import io.github.harshgupta2.resilience.core.domain.Mode;
import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.Slo;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.time.Duration;

/** Builds the example policy from {@code docs/PLAN.md} section G, with one field changed per test. */
public final class TestPolicies {

    public String region = "ap-south-1";
    public String service = "orders";
    public Range replicas = new Range(2, 10);
    public Range poolSizePerPod = new Range(5, 20);
    public Range concurrencyLimit = new Range(20, 200);
    public int dbConnectionBudget = 120;
    public long podMemoryLimitMb = 2048;
    public double memorySafetyFactor = 0.85;
    public double maxStepPercent = 0.2;
    public Duration cooldown = Duration.ofSeconds(300);
    public Mode mode = Mode.RECOMMEND;
    public TargetConfig staticDefault = new TargetConfig(4, 10, 80);
    public BusinessCriticality businessCriticality = BusinessCriticality.HIGH;
    public Slo slo = new Slo(500, 0.01);

    public static TestPolicies valid() {
        return new TestPolicies();
    }

    public RegionPolicy build() {
        return new RegionPolicy(region, service, replicas, poolSizePerPod, concurrencyLimit,
                dbConnectionBudget, podMemoryLimitMb, memorySafetyFactor, maxStepPercent, cooldown,
                mode, staticDefault, businessCriticality, slo);
    }
}
