package com.hila.interviewprep.service;

import com.hila.interviewprep.config.QuoteProperties;
import com.hila.interviewprep.domain.Country;
import com.hila.interviewprep.domain.PricingRules;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.error.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Business rules a well-formed request must pass. Checks run in a fixed order
 * (country, deductible, age) and the first broken rule is reported.
 */
@Component
public class QuoteValidator {

    private final QuoteProperties properties;
    private final PricingRules pricingRules;

    public QuoteValidator(QuoteProperties properties, PricingRules pricingRules) {
        this.properties = properties;
        this.pricingRules = pricingRules;
    }

    /** A request that passed every rule, with the deductible normalized to its allowed value. */
    public record ValidRequest(QuoteRequest request, Country country) {
    }

    public ValidRequest validate(QuoteRequest request, LocalDate today) {
        Country country = Country.fromCode(request.country())
                .orElseThrow(() -> new BusinessRuleException("Country '" + request.country()
                        + "' is not supported. Supported: " + supportedCountries()));

        BigDecimal deductible = pricingRules.supportedDeductible(request.deductible())
                .orElseThrow(() -> new BusinessRuleException("Deductible " + request.deductible().toPlainString()
                        + " is not supported. Allowed: " + allowedDeductibles()));

        checkAge(request.birthDate(), today);

        QuoteRequest normalized = new QuoteRequest(request.petType(), request.breed(), request.birthDate(),
                request.plan(), deductible, request.country());
        return new ValidRequest(normalized, country);
    }

    private void checkAge(LocalDate birthDate, LocalDate today) {
        if (birthDate.isAfter(today)) {
            throw new BusinessRuleException("birthDate " + birthDate + " is in the future");
        }
        if (birthDate.plus(properties.minPetAge()).isAfter(today)) {
            throw new BusinessRuleException("Pet must be at least " + describe(properties.minPetAge()) + " old");
        }
        if (today.isAfter(birthDate.plus(properties.maxPetAge()))) {
            throw new BusinessRuleException("Pet must be at most " + describe(properties.maxPetAge()) + " old");
        }
    }

    private String supportedCountries() {
        return Arrays.stream(Country.values()).map(Country::name).collect(Collectors.joining(", "));
    }

    private String allowedDeductibles() {
        return pricingRules.allowedDeductibles().stream()
                .map(BigDecimal::toPlainString)
                .collect(Collectors.joining(", "));
    }

    /** "14 years", "56 days", "1 year 6 months". */
    private static String describe(Period period) {
        List<String> parts = new ArrayList<>();
        addPart(parts, period.getYears(), "year");
        addPart(parts, period.getMonths(), "month");
        addPart(parts, period.getDays(), "day");
        return parts.isEmpty() ? "0 days" : String.join(" ", parts);
    }

    private static void addPart(List<String> parts, int amount, String unit) {
        if (amount != 0) {
            parts.add(amount + " " + unit + (amount == 1 ? "" : "s"));
        }
    }
}
