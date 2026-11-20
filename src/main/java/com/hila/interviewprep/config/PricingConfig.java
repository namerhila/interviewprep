package com.hila.interviewprep.config;

import com.hila.interviewprep.domain.AgeBand;
import com.hila.interviewprep.domain.Plan;
import com.hila.interviewprep.domain.PricingRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The pricing factors from SPEC.md section 4.1. */
@Configuration
public class PricingConfig {

    @Bean
    public PricingRules pricingRules() {
        return new PricingRules(
                Map.of(
                        Plan.BASIC, new BigDecimal("20.00"),
                        Plan.PREMIUM, new BigDecimal("35.00")),
                List.of(
                        new AgeBand(0, new BigDecimal("1.0")),
                        new AgeBand(1, new BigDecimal("1.2")),
                        new AgeBand(8, new BigDecimal("1.6"))),
                Set.of("French Bulldog", "Bulldog", "Pug", "Persian"),
                new BigDecimal("1.3"),
                Map.of(
                        new BigDecimal("100"), new BigDecimal("1.0"),
                        new BigDecimal("250"), new BigDecimal("0.9"),
                        new BigDecimal("500"), new BigDecimal("0.8")));
    }
}
