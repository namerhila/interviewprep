package com.hila.interviewprep.repository;

import com.hila.interviewprep.domain.PetType;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryQuoteRepositoryTest {

    private final InMemoryQuoteRepository repository = new InMemoryQuoteRepository();

    private static Quote quote(UUID id) {
        Instant createdAt = Instant.parse("2026-11-19T10:00:00Z");
        return new Quote(id, PetType.DOG, "Pug", LocalDate.of(2023, 5, 10), Plan.PREMIUM,
                new BigDecimal("250"), "US", new BigDecimal("35.00"), "USD",
                createdAt, createdAt.plusSeconds(30L * 24 * 3600));
    }

    @Test
    void savedQuoteCanBeFoundById() {
        Quote quote = quote(UUID.randomUUID());

        repository.save(quote);

        assertThat(repository.findById(quote.id())).contains(quote);
    }

    @Test
    void findByUnknownIdIsEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void savingADuplicateIdIsRejectedAndKeepsTheOriginal() {
        UUID id = UUID.randomUUID();
        Quote original = quote(id);
        repository.save(original);

        assertThatThrownBy(() -> repository.save(quote(id)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(id)).containsSame(original);
    }

    @Test
    void concurrentSavesAreAllStored() throws Exception {
        int threads = 50;
        List<UUID> ids = IntStream.range(0, threads).mapToObj(i -> UUID.randomUUID()).toList();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<Quote>> futures = ids.stream()
                    .map(id -> executor.submit(() -> {
                        start.await();
                        return repository.save(quote(id));
                    }))
                    .toList();
            start.countDown();
            for (Future<Quote> future : futures) {
                future.get();
            }
        }

        assertThat(ids).allSatisfy(id -> assertThat(repository.findById(id)).isPresent());
    }
}