package io.github.harshgupta2.resilience.core.domain;

/** How important a service is to the business in a region; set by the product owner. */
public enum BusinessCriticality {
    HIGH,
    MEDIUM,
    LOW
}
