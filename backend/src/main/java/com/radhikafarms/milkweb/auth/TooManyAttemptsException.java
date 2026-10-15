package com.radhikafarms.milkweb.auth;

import java.time.Duration;

public class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super("Too many attempts. Please wait " + minutesText(retryAfter) + " and try again.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Whole seconds until retrying is allowed, rounded up so clients never retry early. */
    public long getRetryAfterSeconds() {
        return Math.max(1, retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0));
    }

    private static String minutesText(Duration retryAfter) {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        long minutes = Math.max(1, (seconds + 59) / 60);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
