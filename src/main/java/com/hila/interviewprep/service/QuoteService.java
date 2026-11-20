package com.hila.interviewprep.service;

import com.hila.interviewprep.config.QuoteProperties;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.domain.QuoteView;
import com.hila.interviewprep.error.QuoteNotFoundException;
import com.hila.interviewprep.repository.QuoteRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class QuoteService {

    private static final String CURRENCY = "USD";

    private final QuoteRepository repository;
    private final Clock clock;
    private final QuoteProperties properties;

    public QuoteService(QuoteRepository repository, Clock clock, QuoteProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    public QuoteView create(QuoteRequest request) {
        Instant now = clock.instant();
        Quote quote = new Quote(
                UUID.randomUUID(),
                request.petType(),
                request.breed(),
                request.birthDate(),
                request.plan(),
                request.deductible(),
                request.country(),
                basePrice(request.plan()),
                CURRENCY,
                now,
                now.plus(properties.validity()));
        repository.save(quote);
        return new QuoteView(quote, quote.statusAt(now));
    }

    public QuoteView get(UUID id) {
        Quote quote = repository.findById(id).orElseThrow(() -> new QuoteNotFoundException(id));
        return new QuoteView(quote, quote.statusAt(clock.instant()));
    }

    // Slice 2 replaces this with the full pricing rules.
    private static BigDecimal basePrice(Plan plan) {
        return switch (plan) {
            case BASIC -> new BigDecimal("20.00");
            case PREMIUM -> new BigDecimal("35.00");
        };
    }
}
