package com.minsu.welocation.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    @Test
    void blankSecretStopsStartupWhenASecretIsRequired() {
        assertThatThrownBy(() -> new JwtConfig().jwtKey(" ", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is required");
    }

    @Test
    void blankSecretFallsBackToARandomKeyOnALaptop() {
        assertThat(new JwtConfig().jwtKey("", false).getEncoded()).hasSize(32);
    }
}
