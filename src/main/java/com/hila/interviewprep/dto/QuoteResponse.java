package com.hila.interviewprep.dto;

import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteStatus;
import com.hila.interviewprep.domain.QuoteView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QuoteResponse(
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
        Instant validUntil,
        QuoteStatus status) {

    public static QuoteResponse from(QuoteView view) {
        Quote q = view.quote();
        return new QuoteResponse(q.id(), q.petType(), q.breed(), q.birthDate(), q.plan(), q.deductible(),
                q.country(), q.monthlyPremium(), q.currency(), q.createdAt(), q.validUntil(), view.status());
    }
}
