package com.hila.interviewprep.error;

import java.util.UUID;

public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException(UUID id) {
        super("Quote " + id + " was not found");
    }
}
