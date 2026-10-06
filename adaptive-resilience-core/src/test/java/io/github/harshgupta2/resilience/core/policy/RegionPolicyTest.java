package io.github.harshgupta2.resilience.core.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionPolicyTest {

    @Test
    void rejectsNullFieldsAtConstruction() {
        TestPolicies policy = TestPolicies.valid();
        policy.staticDefault = null;

        assertThatThrownBy(policy::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("staticDefault");
    }
}
