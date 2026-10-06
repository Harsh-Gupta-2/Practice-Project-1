package io.github.harshgupta2.resilience.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeTest {

    @Test
    void rejectsMinGreaterThanMax() {
        assertThatThrownBy(() -> new Range(5, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min (5) must be <= max (4)");
    }

    @Test
    void rejectsNegativeMin() {
        assertThatThrownBy(() -> new Range(-1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be >= 0");
    }

    @Test
    void allowsSingleValueRange() {
        Range range = new Range(3, 3);

        assertThat(range.contains(3)).isTrue();
        assertThat(range.clamp(10)).isEqualTo(3);
    }

    @Test
    void containsIsInclusiveOnBothEnds() {
        Range range = new Range(2, 10);

        assertThat(range.contains(1)).isFalse();
        assertThat(range.contains(2)).isTrue();
        assertThat(range.contains(10)).isTrue();
        assertThat(range.contains(11)).isFalse();
    }

    @Test
    void clampMovesValuesToNearestBound() {
        Range range = new Range(2, 10);

        assertThat(range.clamp(-5)).isEqualTo(2);
        assertThat(range.clamp(7)).isEqualTo(7);
        assertThat(range.clamp(Integer.MAX_VALUE)).isEqualTo(10);
    }
}
