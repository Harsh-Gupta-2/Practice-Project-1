package io.github.harshgupta2.resilience.core.advisor;

/** What we know about the database behind the service. */
public enum DbHealth {
    /** The database has spare capacity. */
    OK,
    /** The database itself is the bottleneck (high CPU, high query latency, connection limit near). */
    SATURATED,
    /**
     * No database metrics available (for example in embedded mode). Treated like {@link #SATURATED} for
     * anything that would add load, because adding connections to a database we cannot see is the exact
     * mistake this project exists to prevent.
     */
    UNKNOWN
}
