package io.github.harshgupta2.resilience.core.policy;

import io.github.harshgupta2.resilience.core.domain.BusinessCriticality;
import io.github.harshgupta2.resilience.core.domain.Mode;
import io.github.harshgupta2.resilience.core.domain.Range;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.Slo;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.time.Duration;

/** Builds the example policy from {@code docs/PLAN.md} section G, with one field changed per test. */
final class TestPolicies {

    String region = "ap-south-1";
    String service = "orders";
    Range replicas = new Range(2, 10);
    Range poolSizePerPod = new Range(5, 20);
    Range concurrencyLimit = new Range(20, 200);
    int dbConnectionBudget = 120;
    long podMemoryLimitMb = 2048;
    double memorySafetyFactor = 0.85;
    double maxStepPercent = 0.2;
    Duration cooldown = Duration.ofSeconds(300);
    Mode mode = Mode.RECOMMEND;
    TargetConfig staticDefault = new TargetConfig(4, 10, 80);
    BusinessCriticality businessCriticality = BusinessCriticality.HIGH;
    Slo slo = new Slo(500, 0.01);

    static TestPolicies valid() {
        return new TestPolicies();
    }

    RegionPolicy build() {
        return new RegionPolicy(region, service, replicas, poolSizePerPod, concurrencyLimit,
                dbConnectionBudget, podMemoryLimitMb, memorySafetyFactor, maxStepPercent, cooldown,
                mode, staticDefault, businessCriticality, slo);
    }
}
