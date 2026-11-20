package com.hila.interviewprep.service;

import com.hila.interviewprep.config.QuoteProperties;
import com.hila.interviewprep.domain.Quote;
import com.hila.interviewprep.domain.QuoteRequest;
import com.hila.interviewprep.domain.QuoteView;
import com.hila.interviewprep.error.QuoteNotFoundException;
import com.hila.interviewprep.repository.QuoteRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class QuoteService {

    private static final String CURRENCY = "USD";

    private final QuoteRepository repository;
    private final Clock clock;
    private final QuoteProperties properties;
    private final PremiumCalculator premiumCalculator;

    public QuoteService(QuoteRepository repository, Clock clock, QuoteProperties properties,
                        PremiumCalculator premiumCalculator) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.premiumCalculator = premiumCalculator;
    }

    public QuoteView create(QuoteRequest request) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Quote quote = new Quote(
                UUID.randomUUID(),
                request.petType(),
                request.breed(),
                request.birthDate(),
                request.plan(),
                request.deductible(),
                request.country(),
                premiumCalculator.calculate(request, today),
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
}
