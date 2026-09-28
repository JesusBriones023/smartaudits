package com.smartaudits.service;

public class AuditQuotaExceededException
        extends RuntimeException {

    private final long retryAfterSeconds;

    public AuditQuotaExceededException(
            String message,
            long retryAfterSeconds) {

        super(message);

        this.retryAfterSeconds =
                Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}