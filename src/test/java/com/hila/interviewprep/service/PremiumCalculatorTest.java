package com.hila.interviewprep.service;

import com.hila.interviewprep.config.PricingConfig;
import com.hila.interviewprep.domain.AgeBand;
import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.PricingRules;
import com.hila.interviewprep.domain.QuoteRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PremiumCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate PUPPY = LocalDate.of(2026, 5, 20); // 6 months old

    private final PremiumCalculator calculator = new PremiumCalculator(new PricingConfig().pricingRules());

    private static QuoteRequest request(Plan plan, LocalDate birthDate, String breed, String deductible) {
        return new QuoteRequest(PetType.DOG, breed, birthDate, plan, new BigDecimal(deductible), "US");
    }

    @ParameterizedTest(name = "{0}, born {1}, {2}, deductible {3} -> {4}")
    @CsvSource({
            "PREMIUM, 2023-11-20, Pug,      250, 49.14",  // 35 x 1.2 x 1.3 x 0.9
            "BASIC,   2026-05-20,         , 100, 20.00",  // 20 x 1.0 x 1.0 x 1.0, no breed
            "BASIC,   2018-11-20, persian,  500, 33.28",  // 20 x 1.6 x 1.3 x 0.8
            "PREMIUM, 2016-11-20, Labrador, 100, 56.00"   // 35 x 1.6 x 1.0 x 1.0
    })
    void workedExamplesFromTheSpec(Plan plan, LocalDate birthDate, String breed, String deductible, String expected) {
        BigDecimal premium = calculator.calculate(request(plan, birthDate, breed, deductible), TODAY);

        assertThat(premium).isEqualByComparingTo(expected);
    }

    @Test
    void noBreedMeansNoBreedFactor() {
        assertThat(calculator.calculate(request(Plan.BASIC, PUPPY, null, "100"), TODAY)).isEqualByComparingTo("20.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pug", "  pUg ", "FRENCH BULLDOG", "French Bulldog", "Bulldog", "persian"})
    void highRiskBreedsMatchCaseInsensitivelyAndTrimmed(String breed) {
        // 20 x 1.3
        assertThat(calculator.calculate(request(Plan.BASIC, PUPPY, breed, "100"), TODAY)).isEqualByComparingTo("26.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bulldog Mix", "Labrador", "French", "Pugs", "Persian Cat"})
    void otherBreedsAndPartialNamesAreNotHighRisk(String breed) {
        assertThat(calculator.calculate(request(Plan.BASIC, PUPPY, breed, "100"), TODAY)).isEqualByComparingTo("20.00");
    }

    @ParameterizedTest(name = "born {0} -> {1}")
    @CsvSource({
            "2025-11-21, 20.00",  // day before 1st birthday: under 1 -> x1.0
            "2025-11-20, 24.00",  // on 1st birthday: 1 year -> x1.2
            "2018-11-21, 24.00",  // day before 8th birthday: 7 years -> x1.2
            "2018-11-20, 32.00",  // on 8th birthday: 8 years -> x1.6
            "2012-11-20, 32.00"   // 14 years -> x1.6
    })
    void ageFactorBoundaries(LocalDate birthDate, String expected) {
        assertThat(calculator.calculate(request(Plan.BASIC, birthDate, null, "100"), TODAY)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "deductible {0} -> {1}")
    @CsvSource({"100, 20.00", "100.00, 20.00", "250, 18.00", "500, 16.00"})
    void deductibleFactorMatchesByValueNotScale(String deductible, String expected) {
        assertThat(calculator.calculate(request(Plan.BASIC, PUPPY, null, deductible), TODAY)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"BASIC, 2026-05-20", "PREMIUM, 2016-11-20"}) // 20.00 and 35 x 1.6 = 56.0
    void premiumAlwaysHasTwoDecimals(Plan plan, LocalDate birthDate) {
        assertThat(calculator.calculate(request(plan, birthDate, null, "100"), TODAY).scale()).isEqualTo(2);
    }

    /** The real factors never produce more than 2 decimals, so rounding is tested with made-up rules. */
    @ParameterizedTest(name = "age factor {0} x deductible factor {1} -> {2}")
    @CsvSource({
            "1.0005, 1,      10.01",  // 10.005 -> HALF_UP gives 10.01 (HALF_EVEN would give 10.00)
            "1.0004, 1,      10.00",  // 10.004 -> 10.00
            "1.0005, 1.0005, 10.01"   // 10.0100025 -> 10.01; rounding after each step would give 10.02
    })
    void roundsOnceAtTheEndHalfUp(String ageFactor, String deductibleFactor, String expected) {
        PricingRules rules = new PricingRules(
                Map.of(Plan.BASIC, new BigDecimal("10.00")),
                List.of(new AgeBand(0, new BigDecimal(ageFactor))),
                Set.of(),
                BigDecimal.ONE,
                Map.of(new BigDecimal("100"), new BigDecimal(deductibleFactor)));

        BigDecimal premium = new PremiumCalculator(rules).calculate(request(Plan.BASIC, PUPPY, null, "100"), TODAY);

        assertThat(premium).isEqualTo(new BigDecimal(expected)); // equals also checks scale 2
    }
}
