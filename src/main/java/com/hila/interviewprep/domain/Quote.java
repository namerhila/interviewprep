package com.hila.interviewprep.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Quote(
        UUID id,
        PetType petType,
        String breed,
        LocalDate birthDate,
        Plan plan,
        BigDecimal deductible,
        String country,
        BigDecimal monthlyPremium,
        String currency,
        Instant createdAt,
        Instant validUntil) {

    /** A quote is active while {@code now} is before {@code validUntil}, and expired from then on. */
    public QuoteStatus statusAt(Instant now) {
        return now.isBefore(validUntil) ? QuoteStatus.ACTIVE : QuoteStatus.EXPIRED;
    }
}
