package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.TestPolicies;
import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.time.Instant;
import java.util.Optional;

final class GuardTestSupport {

    static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    /** Plan example: replicas [2,10], pool [5,20], concurrency [20,200], budget 120, step 20%, 2048 MB x 0.85. */
    static final RegionPolicy POLICY = TestPolicies.valid().build();

    private GuardTestSupport() {
    }

    static GuardContext context(TargetConfig current) {
        return new GuardContext(POLICY, current, NOW, Optional.empty(), false, Optional.empty());
    }

    static GuardContext context(RegionPolicy policy, TargetConfig current) {
        return new GuardContext(policy, current, NOW, Optional.empty(), false, Optional.empty());
    }
}
