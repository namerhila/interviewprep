package com.hila.interviewprep.error;

/** A well-formed request that breaks a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
