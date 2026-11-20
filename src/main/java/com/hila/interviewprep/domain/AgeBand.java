package com.hila.interviewprep.domain;

import java.math.BigDecimal;

/** Pets aged {@code minYears} full years or more get {@code factor}, until the next band starts. */
public record AgeBand(int minYears, BigDecimal factor) {
}
