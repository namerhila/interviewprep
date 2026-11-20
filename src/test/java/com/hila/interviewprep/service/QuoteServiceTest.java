package com.hila.interviewprep.service;

import com.hila.interviewprep.config.PricingConfig;
import com.hila.interviewprep.config.QuoteProperties;
import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.domain.QuoteStatus;
import com.hila.interviewprep.domain.QuoteView;
import com.hila.interviewprep.error.QuoteNotFoundException;
import com.hila.interviewprep.repository.InMemoryQuoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final QuoteProperties PROPERTIES = new QuoteProperties(Duration.ofDays(30));

    private final InMemoryQuoteRepository repository = new InMemoryQuoteRepository();
    private final QuoteService service = serviceAt(NOW);

    private QuoteService serviceAt(Instant instant) {
        return new QuoteService(repository, Clock.fixed(instant, ZoneOffset.UTC), PROPERTIES,
                new PremiumCalculator(new PricingConfig().pricingRules()));
    }

    /** A 6-month-old pet with no breed and deductible 100, so the premium equals the base price. */
    private static QuoteRequest puppyRequest(Plan plan) {
        return new QuoteRequest(PetType.DOG, null, LocalDate.of(2026, 5, 19), plan, new BigDecimal("100"), "US");
    }

    @Test
    void createReturnsQuoteWithGeneratedIdAndRequestDetails() {
        QuoteRequest request = new QuoteRequest(PetType.CAT, "Siamese", LocalDate.of(2026, 5, 19),
                Plan.BASIC, new BigDecimal("100"), "US");

        Quote quote = service.create(request).quote();

        assertThat(quote.id()).isNotNull();
        assertThat(quote.petType()).isEqualTo(PetType.CAT);
        assertThat(quote.breed()).isEqualTo("Siamese");
        assertThat(quote.birthDate()).isEqualTo(LocalDate.of(2026, 5, 19));
        assertThat(quote.plan()).isEqualTo(Plan.BASIC);
        assertThat(quote.deductible()).isEqualByComparingTo("100");
        assertThat(quote.country()).isEqualTo("US");
        assertThat(quote.currency()).isEqualTo("USD");
    }

    @Test
    void createSetsCreatedAtFromClockAndValidUntilThirtyDaysLater() {
        Quote quote = service.create(puppyRequest(Plan.BASIC)).quote();

        assertThat(quote.createdAt()).isEqualTo(NOW);
        assertThat(quote.validUntil()).isEqualTo(Instant.parse("2026-12-19T10:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({"BASIC, 20.00", "PREMIUM, 35.00"})
    void premiumIsBasePriceOfPlanWithTwoDecimals(Plan plan, String expected) {
        Quote quote = service.create(puppyRequest(plan)).quote();

        assertThat(quote.monthlyPremium()).isEqualByComparingTo(expected);
        assertThat(quote.monthlyPremium().scale()).isEqualTo(2);
    }

    @Test
    void newQuoteIsActive() {
        assertThat(service.create(puppyRequest(Plan.BASIC)).status()).isEqualTo(QuoteStatus.ACTIVE);
    }

    @Test
    void createdQuoteCanBeFetchedById() {
        Quote created = service.create(puppyRequest(Plan.PREMIUM)).quote();

        QuoteView fetched = service.get(created.id());

        assertThat(fetched.quote()).isEqualTo(created);
        assertThat(fetched.status()).isEqualTo(QuoteStatus.ACTIVE);
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(unknown))
                .isInstanceOf(QuoteNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void sameRequestTwiceCreatesTwoDistinctQuotes() {
        Quote first = service.create(puppyRequest(Plan.BASIC)).quote();
        Quote second = service.create(puppyRequest(Plan.BASIC)).quote();

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(service.get(first.id()).quote()).isEqualTo(first);
        assertThat(service.get(second.id()).quote()).isEqualTo(second);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-19T10:00:00Z, ACTIVE",   // right after creation
            "2026-12-19T09:59:59Z, ACTIVE",   // one second before validUntil
            "2026-12-19T10:00:00Z, EXPIRED",  // exactly validUntil
            "2026-12-20T10:00:00Z, EXPIRED"   // a day after validUntil
    })
    void statusIsComputedFromTheClockAtReadTime(String readAt, QuoteStatus expected) {
        Quote created = service.create(puppyRequest(Plan.BASIC)).quote();

        QuoteView fetched = serviceAt(Instant.parse(readAt)).get(created.id());

        assertThat(fetched.status()).isEqualTo(expected);
    }

    @Test
    void workedExampleThroughTheService() {
        // PREMIUM, 3 years old on 2026-11-19, Pug, deductible 250: 35 x 1.2 x 1.3 x 0.9
        QuoteRequest request = new QuoteRequest(PetType.DOG, "Pug", LocalDate.of(2023, 11, 19),
                Plan.PREMIUM, new BigDecimal("250"), "US");

        assertThat(service.create(request).quote().monthlyPremium()).isEqualByComparingTo("49.14");
    }

    @Test
    void noBreedMeansNoBreedFactor() {
        // PREMIUM, 10 years old, no breed, deductible 100: 35 x 1.6
        QuoteRequest request = new QuoteRequest(PetType.CAT, null, LocalDate.of(2016, 11, 19),
                Plan.PREMIUM, new BigDecimal("100"), "US");

        Quote quote = service.create(request).quote();

        assertThat(quote.breed()).isNull();
        assertThat(quote.monthlyPremium()).isEqualByComparingTo("56.00");
    }

    @Test
    void breedIsStoredAndReturnedTrimmed() {
        QuoteRequest request = new QuoteRequest(PetType.DOG, "  Pug ", LocalDate.of(2026, 5, 19),
                Plan.BASIC, new BigDecimal("100"), "US");

        Quote created = service.create(request).quote();

        assertThat(created.breed()).isEqualTo("Pug");
        assertThat(service.get(created.id()).quote().breed()).isEqualTo("Pug");
        assertThat(created.monthlyPremium()).isEqualByComparingTo("26.00"); // still high-risk: 20 x 1.3
    }

    @Test
    void todayIsTheUtcDate() {
        // 03:00 UTC on Nov 20 is still Nov 19 in the US, but in UTC the pet has turned 1: 20 x 1.2
        QuoteService earlyMorningUtc = serviceAt(Instant.parse("2026-11-20T03:00:00Z"));
        QuoteRequest request = new QuoteRequest(PetType.DOG, null, LocalDate.of(2025, 11, 20),
                Plan.BASIC, new BigDecimal("100"), "US");

        assertThat(earlyMorningUtc.create(request).quote().monthlyPremium()).isEqualByComparingTo("24.00");
    }
}