package com.hila.interviewprep.repository;

import com.hila.interviewprep.domain.Quote;

import java.util.Optional;
import java.util.UUID;

/** Storage for quotes. Implementations must be thread-safe. */
public interface QuoteRepository {

    /** Stores a new quote. Throws {@link IllegalStateException} if a quote with the same id already exists. */
    Quote save(Quote quote);

    Optional<Quote> findById(UUID id);
}
