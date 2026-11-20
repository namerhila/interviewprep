package com.hila.interviewprep.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Quote settings, bound from {@code quote.*} properties. */
@ConfigurationProperties("quote")
public record QuoteProperties(@DefaultValue("30d") Duration validity) {
}
