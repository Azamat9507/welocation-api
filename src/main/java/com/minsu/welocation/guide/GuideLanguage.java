package com.minsu.welocation.guide;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

// The language the guide writes its own words in. Spot names and other database values are never translated.
public enum GuideLanguage {
    KO("ko", "Korean"),
    EN("en", "English"),
    RU("ru", "Russian"),
    UZ("uz", "Uzbek, in Latin script");

    private final String code;
    private final String inEnglish;

    GuideLanguage(String code, String inEnglish) {
        this.code = code;
        this.inEnglish = inEnglish;
    }

    @JsonValue
    public String code() {
        return code;
    }

    // Exact codes only: without this an empty string was read as null and quietly became Korean.
    @JsonCreator
    static GuideLanguage of(String code) {
        return Arrays.stream(values()).filter(language -> language.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown language"));
    }

    // Goes into the system prompt as is, so it must stay a constant here; never build it from request text.
    String inEnglish() {
        return inEnglish;
    }
}
