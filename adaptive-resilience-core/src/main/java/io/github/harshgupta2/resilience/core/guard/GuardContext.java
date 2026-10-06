package io.github.harshgupta2.resilience.core.guard;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the guard needs besides the proposed target.
 *
 * @param policy        validated policy for the service and region
 * @param current       target that is applied right now
 * @param now           current time
 * @param lastAppliedAt when a change was last applied; empty if never
 * @param frozen        true when a human or an incident froze automatic changes
 * @param memoryProfile pod memory figures; empty when not known yet
 */
public record GuardContext(
        RegionPolicy policy,
        TargetConfig current,
        Instant now,
        Optional<Instant> lastAppliedAt,
        boolean frozen,
        Optional<MemoryProfile> memoryProfile) {

    public GuardContext {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lastAppliedAt, "lastAppliedAt");
        Objects.requireNonNull(memoryProfile, "memoryProfile");
    }
}
