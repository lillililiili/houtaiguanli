package com.uav.lowaltitude.integration.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NetworkTargetPolicyTest {

    private final NetworkTargetPolicy policy = new NetworkTargetPolicy(false);

    @Test
    void acceptsOnlyEveryResolvedAddressInsideAllowlist() {
        assertThat(policy.resolveAllowed("192.0.2.88", "192.0.2.0/24")).hasSize(1);
        assertThatThrownBy(() -> policy.resolveAllowed("192.0.2.88", "198.51.100.0/24"))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("CIDR");
    }

    @Test
    void loopbackLinkLocalMetadataAndMissingAllowlistAreAlwaysRejected() {
        assertThatThrownBy(() -> policy.resolveAllowed("127.0.0.1", "127.0.0.0/8"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> policy.resolveAllowed("169.254.169.254", "169.254.0.0/16"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> policy.resolveAllowed("192.0.2.10", ""))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void listedLoopbackIsAcceptedOnlyWhenFlagEnabled() {
        NetworkTargetPolicy allowed = new NetworkTargetPolicy(true);
        assertThat(allowed.resolveAllowed("127.0.0.1", "127.0.0.1/32")).hasSize(1);
        assertThatThrownBy(() -> allowed.resolveAllowed("127.0.0.1", "192.0.2.0/24"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> policy.resolveAllowed("127.0.0.1", "127.0.0.1/32"))
                .isInstanceOf(ProtocolException.class);
    }
}
