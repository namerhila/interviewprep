package com.hila.interviewprep.controller;

import com.hila.interviewprep.domain.QuoteView;
import com.hila.interviewprep.dto.CreateQuoteRequest;
import com.hila.interviewprep.dto.QuoteResponse;
import com.hila.interviewprep.service.QuoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    public ResponseEntity<QuoteResponse> create(@Valid @RequestBody CreateQuoteRequest request) {
        QuoteView created = quoteService.create(request.toDomain());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.quote().id())
                .toUri();
        return ResponseEntity.created(location).body(QuoteResponse.from(created));
    }

    @GetMapping("/{id}")
    public QuoteResponse get(@PathVariable UUID id) {
        return QuoteResponse.from(quoteService.get(id));
    }
}
