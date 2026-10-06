package io.github.harshgupta2.resilience.core.advisor;

import io.github.harshgupta2.resilience.core.domain.TargetConfig;

import java.util.List;
import java.util.Objects;

/**
 * What an advisor proposes, with the reasons in plain words. The guard decides what is actually applied.
 */
public record Suggestion(TargetConfig target, List<String> reasons) {

    public Suggestion {
        Objects.requireNonNull(target, "target");
        reasons = List.copyOf(reasons);
    }
}
