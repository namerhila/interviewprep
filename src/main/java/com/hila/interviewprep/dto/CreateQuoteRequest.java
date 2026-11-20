package com.hila.interviewprep.dto;

import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.QuoteRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateQuoteRequest(
        PetType petType,
        String breed,
        LocalDate birthDate,
        Plan plan,
        BigDecimal deductible,
        String country) {

    public QuoteRequest toDomain() {
        return new QuoteRequest(petType, breed, birthDate, plan, deductible, country);
    }
}
