package io.github.harshgupta2.resilience.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetConfigTest {

    @Test
    void totalConnectionsIsReplicasTimesPoolSize() {
        assertThat(new TargetConfig(4, 10, 80).totalConnections()).isEqualTo(40);
    }

    @Test
    void totalConnectionsDoesNotOverflowInt() {
        TargetConfig huge = new TargetConfig(Integer.MAX_VALUE, 2, 1);

        assertThat(huge.totalConnections()).isEqualTo(2L * Integer.MAX_VALUE);
    }

    @Test
    void withersChangeOnlyOneValue() {
        TargetConfig original = new TargetConfig(4, 10, 80);

        assertThat(original.withReplicas(5)).isEqualTo(new TargetConfig(5, 10, 80));
        assertThat(original.withPoolSizePerPod(12)).isEqualTo(new TargetConfig(4, 12, 80));
        assertThat(original.withConcurrencyLimit(64)).isEqualTo(new TargetConfig(4, 10, 64));
        assertThat(original).isEqualTo(new TargetConfig(4, 10, 80));
    }
}
