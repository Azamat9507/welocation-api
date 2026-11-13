package com.minsu.welocation.user;

import java.util.Locale;
import java.util.Optional;

public enum IdentityProvider {
    GOOGLE,
    KAKAO;

    // "google" in a URL -> GOOGLE; anything else is not a provider
    public static Optional<IdentityProvider> fromPath(String value) {
        for (IdentityProvider p : values()) {
            if (p.name().toLowerCase(Locale.ROOT).equals(value)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }
}
