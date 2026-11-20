package com.hila.interviewprep.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The input the service needs to create a quote. The breed is stored trimmed. */
public record QuoteRequest(
        PetType petType,
        String breed,
        LocalDate birthDate,
        Plan plan,
        BigDecimal deductible,
        String country) {

    public QuoteRequest {
        breed = breed == null ? null : breed.strip();
    }
}
