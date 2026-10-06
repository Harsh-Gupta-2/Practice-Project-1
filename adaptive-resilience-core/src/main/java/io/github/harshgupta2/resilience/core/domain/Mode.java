package io.github.harshgupta2.resilience.core.domain;

/** How decisions for a region are used. See {@code docs/PLAN.md}, section C. */
public enum Mode {
    /** Decisions are computed and audited, never applied. */
    SHADOW,
    /** Decisions wait for a human approval before they are applied. */
    RECOMMEND,
    /** Small changes inside the guard are applied automatically; large ones still need approval. */
    AUTO
}
