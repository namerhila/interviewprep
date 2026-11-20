package com.hila.interviewprep.service;

import com.hila.interviewprep.domain.PricingRules;
import com.hila.interviewprep.domain.QuoteRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/** Monthly premium = base price x age factor x breed factor x deductible factor, rounded once at the end. */
@Component
public class PremiumCalculator {

    private final PricingRules rules;

    public PremiumCalculator(PricingRules rules) {
        this.rules = rules;
    }

    public BigDecimal calculate(QuoteRequest request, LocalDate today) {
        int ageInYears = Period.between(request.birthDate(), today).getYears();
        return rules.basePrice(request.plan())
                .multiply(rules.ageFactor(ageInYears))
                .multiply(rules.breedFactor(request.breed()))
                .multiply(rules.deductibleFactor(request.deductible()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
