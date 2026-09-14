package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void numericOnlyTemporaryPasswordIsRejected() {
        assertThatThrownBy(() -> policy.validateTemporary("12345678", "ff2"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION");
                    assertThat(api.getMessage()).contains("大写字母");
                });
    }

    @Test
    void complexTemporaryPasswordIsAccepted() {
        policy.validateTemporary("TempUser#2026A", "ff2");
    }
}
