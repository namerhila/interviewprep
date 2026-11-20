package com.hila.interviewprep.domain;

import java.util.Arrays;
import java.util.Optional;

/** Supported countries and the currency each is quoted in. Add a constant to support a new country. */
public enum Country {
    US("USD");

    private final String currency;

    Country(String currency) {
        this.currency = currency;
    }

    public String currency() {
        return currency;
    }

    /** Exact, case-sensitive match on the ISO code: "us" is not "US". */
    public static Optional<Country> fromCode(String code) {
        return Arrays.stream(values()).filter(country -> country.name().equals(code)).findFirst();
    }
}
