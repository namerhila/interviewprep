package com.hila.interviewprep.dto;

import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.QuoteRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Shape checks only. Business rules (allowed values, age range) live in the service. */
public record CreateQuoteRequest(
        @NotNull PetType petType,
        @Size(min = 1, max = 50) String breed,
        @NotNull LocalDate birthDate,
        @NotNull Plan plan,
        @NotNull BigDecimal deductible,
        @NotNull String country) {

    /** The breed is trimmed first, so the length check applies to the trimmed value and blank becomes "". */
    public CreateQuoteRequest {
        breed = breed == null ? null : breed.strip();
    }

    public QuoteRequest toDomain() {
        return new QuoteRequest(petType, breed, birthDate, plan, deductible, country);
    }
}
