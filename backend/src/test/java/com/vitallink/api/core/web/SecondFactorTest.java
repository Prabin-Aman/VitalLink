package com.vitallink.api.core.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.security.Claims;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A password alone must not reach health data once two-step verification is on. */
class SecondFactorTest {

    static Claims token(String aal) {
        return new Claims(UUID.randomUUID(), "ana@example.com", true, aal, 0, null, null);
    }

    @Test
    void aPasswordOnlyTokenIsRefusedWhenTheAccountHasAFactor() {
        assertThatThrownBy(() -> WebConfig.requireSecondFactor(token("aal1"), id -> true))
                .isInstanceOfSatisfying(
                        ApiError.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo("mfa_required"));
    }

    @Test
    void aTokenWithTheCodeOrAnAccountWithoutAFactorPasses() {
        assertThatCode(() -> WebConfig.requireSecondFactor(token("aal2"), id -> true))
                .doesNotThrowAnyException();
        assertThatCode(() -> WebConfig.requireSecondFactor(token("aal1"), id -> false))
                .doesNotThrowAnyException();
    }
}
