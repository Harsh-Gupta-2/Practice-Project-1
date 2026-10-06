package io.github.harshgupta2.resilience.core.policy;

import java.util.List;

/**
 * Outcome of validating a policy. Holds every problem found, so a caller (for example a REST API) can
 * report them all in one response instead of one at a time.
 */
public record ValidationResult(List<String> errors) {

    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
