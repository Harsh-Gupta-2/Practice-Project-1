package io.github.harshgupta2.resilience.core.advisor;

import io.github.harshgupta2.resilience.core.domain.RegionPolicy;
import io.github.harshgupta2.resilience.core.domain.TargetConfig;

/**
 * Proposes a new target. Implementations: {@link RuleBasedAdvisor} (always available) and, later, an
 * LLM-based advisor. Users can plug in their own. Whatever an advisor returns goes through the guard.
 */
@FunctionalInterface
public interface Advisor {

    Suggestion suggest(RegionSignals signals, RegionPolicy policy, TargetConfig current);
}
