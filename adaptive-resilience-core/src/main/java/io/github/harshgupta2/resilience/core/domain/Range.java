package io.github.harshgupta2.resilience.core.domain;

/**
 * Inclusive integer range, used for the min-max limits that business and engineers agree on.
 *
 * <p>A range with {@code min > max} or a negative bound has no meaning anywhere in the system, so it
 * cannot be constructed. Rules that depend on what the range is used for (for example "a pool needs at
 * least one connection") live in {@link io.github.harshgupta2.resilience.core.policy.PolicyValidator}.
 */
public record Range(int min, int max) {

    public Range {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0, was " + min);
        }
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
    }

    public boolean contains(int value) {
        return value >= min && value <= max;
    }

    /** Returns {@code value} moved to the nearest bound when it lies outside the range. */
    public int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }
}
