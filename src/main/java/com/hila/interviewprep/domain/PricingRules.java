package com.hila.interviewprep.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Every pricing factor, kept in one place so a rule change never touches the service flow. */
public record PricingRules(
        Map<Plan, BigDecimal> basePrices,
        List<AgeBand> ageBands,
        Set<String> highRiskBreeds,
        BigDecimal highRiskBreedFactor,
        Map<BigDecimal, BigDecimal> deductibleFactors) {

    public PricingRules {
        basePrices = Map.copyOf(basePrices);
        ageBands = ageBands.stream().sorted(Comparator.comparingInt(AgeBand::minYears)).toList();
        highRiskBreeds = highRiskBreeds.stream().map(PricingRules::normalize).collect(Collectors.toUnmodifiableSet());
        deductibleFactors = Map.copyOf(deductibleFactors);
    }

    public BigDecimal basePrice(Plan plan) {
        BigDecimal price = basePrices.get(plan);
        if (price == null) {
            throw new IllegalArgumentException("No base price for plan " + plan);
        }
        return price;
    }

    /** The factor of the highest band that starts at or below {@code ageInYears}. */
    public BigDecimal ageFactor(int ageInYears) {
        return ageBands.reversed().stream()
                .filter(band -> band.minYears() <= ageInYears)
                .findFirst()
                .map(AgeBand::factor)
                .orElseThrow(() -> new IllegalArgumentException("No age band for " + ageInYears + " years"));
    }

    /** Whole-name match, case-insensitive and trimmed. No breed means no breed factor. */
    public BigDecimal breedFactor(String breed) {
        return breed != null && highRiskBreeds.contains(normalize(breed)) ? highRiskBreedFactor : BigDecimal.ONE;
    }

    /** Matches by value, so 100 and 100.00 are the same deductible. */
    public BigDecimal deductibleFactor(BigDecimal deductible) {
        return deductibleFactors.entrySet().stream()
                .filter(entry -> entry.getKey().compareTo(deductible) == 0)
                .findFirst()
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new IllegalArgumentException("No factor for deductible " + deductible));
    }

    private static String normalize(String breed) {
        return breed.strip().toLowerCase(Locale.ROOT);
    }
}
