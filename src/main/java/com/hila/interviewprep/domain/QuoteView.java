package com.hila.interviewprep.domain;

/** A quote together with its status at the time it was read. */
public record QuoteView(Quote quote, QuoteStatus status) {
}
