package com.hila.interviewprep.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The input the service needs to create a quote. */
public record QuoteRequest(
        PetType petType,
        String breed,
        LocalDate birthDate,
        Plan plan,
        BigDecimal deductible,
        String country) {
}
