package com.hila.interviewprep.service;

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
        return new QuoteService(repository, Clock.fixed(instant, ZoneOffset.UTC), PROPERTIES);
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
}