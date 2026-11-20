package com.hila.interviewprep.service;

import com.hila.interviewprep.config.PricingConfig;
import com.hila.interviewprep.config.QuoteProperties;
import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.PricingRules;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.error.BusinessRuleException;
import com.hila.interviewprep.repository.InMemoryQuoteRepository;
import com.hila.interviewprep.repository.QuoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Business rules that reject a well-formed request (422). Today is 2026-11-19 (UTC). */
class QuoteServiceBusinessRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final LocalDate PUPPY = LocalDate.of(2026, 5, 19); // 6 months old
    private static final QuoteProperties DEFAULTS =
            new QuoteProperties(Duration.ofDays(30), Period.ofWeeks(8), Period.ofYears(14));

    private final SaveCountingRepository repository = new SaveCountingRepository();
    private final QuoteService service = service(DEFAULTS);

    private QuoteService service(QuoteProperties properties) {
        PricingRules rules = new PricingConfig().pricingRules();
        return new QuoteService(repository, Clock.fixed(NOW, ZoneOffset.UTC), properties,
                new PremiumCalculator(rules), new QuoteValidator(properties, rules));
    }

    private static QuoteRequest request(LocalDate birthDate, String deductible, String country) {
        return new QuoteRequest(PetType.DOG, null, birthDate, Plan.BASIC, new BigDecimal(deductible), country);
    }

    private static QuoteRequest bornOn(LocalDate birthDate) {
        return request(birthDate, "100", "US");
    }

    private void assertRejected(QuoteRequest request, String messagePart) {
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(messagePart);
        assertThat(repository.saves()).as("nothing stored after a rejected request").isZero();
    }

    // --- Age (AC7, AC8, AC9) ---

    @Test
    void exactlyEightWeeksOldIsAccepted() {
        assertThat(service.create(bornOn(LocalDate.of(2026, 9, 24))).quote()).isNotNull(); // 56 days
    }

    @Test
    void oneDayYoungerThanEightWeeksIsRejected() {
        assertRejected(bornOn(LocalDate.of(2026, 9, 25)), "at least 56 days old"); // 55 days
    }

    @Test
    void onTheFourteenthBirthdayIsAccepted() {
        assertThat(service.create(bornOn(LocalDate.of(2012, 11, 19))).quote()).isNotNull();
    }

    @Test
    void oneDayOlderThanFourteenYearsIsRejected() {
        assertRejected(bornOn(LocalDate.of(2012, 11, 18)), "at most 14 years old");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-20", "2027-12-01"})
    void birthDateInTheFutureIsRejected(String birthDate) {
        assertRejected(bornOn(LocalDate.parse(birthDate)), "in the future");
    }

    // --- Deductible (AC20) ---

    @ParameterizedTest
    @ValueSource(strings = {"0", "-100", "99.99", "100.5", "300"})
    void unsupportedDeductibleIsRejected(String deductible) {
        assertRejected(request(PUPPY, deductible, "US"), "Deductible " + deductible + " is not supported");
    }

    @Test
    void deductibleWithDecimalsIsNormalizedToTheAllowedValue() {
        Quote quote = service.create(request(PUPPY, "100.00", "US")).quote();

        assertThat(quote.deductible()).isEqualTo(new BigDecimal("100")); // equals checks the scale too
        assertThat(quote.monthlyPremium()).isEqualByComparingTo("20.00");
    }

    // --- Country (AC21) ---

    @ParameterizedTest
    @ValueSource(strings = {"IL", "us", "USA", "", " US"})
    void unsupportedCountryIsRejected(String country) {
        assertRejected(request(PUPPY, "100", country), "Country '" + country + "' is not supported");
    }

    @Test
    void usIsAcceptedAndQuotedInUsd() {
        Quote quote = service.create(request(PUPPY, "100", "US")).quote();

        assertThat(quote.country()).isEqualTo("US");
        assertThat(quote.currency()).isEqualTo("USD");
    }

    // --- Several broken rules: the first one wins (AC29) ---

    @Test
    void countryIsCheckedBeforeDeductibleAndAge() {
        assertRejected(request(LocalDate.of(2000, 1, 1), "300", "IL"), "Country 'IL'");
    }

    @Test
    void deductibleIsCheckedBeforeAge() {
        assertRejected(request(LocalDate.of(2000, 1, 1), "300", "US"), "Deductible 300");
    }

    // --- Limits are configurable (SPEC 4.2) ---

    @Test
    void ageLimitsComeFromProperties() {
        QuoteService oneToTwoYearsOnly = service(
                new QuoteProperties(Duration.ofDays(30), Period.ofYears(1), Period.ofYears(2)));

        assertThatThrownBy(() -> oneToTwoYearsOnly.create(bornOn(PUPPY)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 1 year old");
        assertThatThrownBy(() -> oneToTwoYearsOnly.create(bornOn(LocalDate.of(2023, 11, 19))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at most 2 years old");
        assertThat(oneToTwoYearsOnly.create(bornOn(LocalDate.of(2025, 5, 19))).quote()).isNotNull();
    }

    /** The real in-memory repository, plus a count of saves so tests can prove nothing was stored. */
    private static final class SaveCountingRepository implements QuoteRepository {
        private final InMemoryQuoteRepository delegate = new InMemoryQuoteRepository();
        private final AtomicInteger saves = new AtomicInteger();

        @Override
        public Quote save(Quote quote) {
            saves.incrementAndGet();
            return delegate.save(quote);
        }

        @Override
        public Optional<Quote> findById(UUID id) {
            return delegate.findById(id);
        }

        int saves() {
            return saves.get();
        }
    }
}
