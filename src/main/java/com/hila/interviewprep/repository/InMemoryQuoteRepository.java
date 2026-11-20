package com.hila.interviewprep.repository;

import com.hila.interviewprep.domain.Quote;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryQuoteRepository implements QuoteRepository {

    private final Map<UUID, Quote> quotes = new ConcurrentHashMap<>();

    @Override
    public Quote save(Quote quote) {
        Quote existing = quotes.putIfAbsent(quote.id(), quote);
        if (existing != null) {
            throw new IllegalStateException("Quote " + quote.id() + " already exists");
        }
        return quote;
    }

    @Override
    public Optional<Quote> findById(UUID id) {
        return Optional.ofNullable(quotes.get(id));
    }
}
