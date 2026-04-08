package org.elasticsearch.jingra.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Utility for executing operations with retry logic for transient failures.
 * Retries on network/timeout errors but not on validation or programming errors.
 */
public final class RetryHelper {
    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);

    private RetryHelper() {
        // Utility class
    }

    /**
     * Functional interface for operations that can be retried.
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    /** Maximum backoff time between retries (60 seconds). */
    private static final long MAX_BACKOFF_MS = 60000;

    /**
     * Computes delay before the next retry (exponential growth capped at {@code 2^6} and {@link #MAX_BACKOFF_MS}).
     * Same formula as {@link #executeWithRetry}. {@code attempt} is 0-based (first wait after first failure uses {@code 0}).
     * Public for callers that need the same delay between steps without nesting {@link #executeWithRetry}.
     */
    public static long computeRetryDelayMs(int attempt, long backoffMs) {
        long exponentialDelay = backoffMs * (1L << Math.min(attempt, 6));
        return Math.min(exponentialDelay, MAX_BACKOFF_MS);
    }

    private static void pauseMillis(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", ie);
        }
    }

    /**
     * Execute an operation with retry logic for transient failures.
     * Retries infinitely for transient errors with capped exponential backoff.
     *
     * @param operation the operation to execute
     * @param maxRetries maximum number of retries (use Integer.MAX_VALUE for infinite retries)
     * @param backoffMs base backoff time in milliseconds between retries
     * @return the result of the operation
     * @throws RuntimeException if non-transient error occurs
     */
    public static <T> T executeWithRetry(RetryableOperation<T> operation, int maxRetries, long backoffMs) {
        int attempt = 0;

        while (true) {
            try {
                return operation.execute();
            } catch (Exception e) {
                // Check if error is transient (retryable)
                if (!isTransientError(e)) {
                    // Non-transient error - fail immediately without retry
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                    throw new RuntimeException("Non-transient error during operation", e);
                }

                // Transient error - retry with exponential backoff (capped)
                long delay = computeRetryDelayMs(attempt, backoffMs);

                String retryMessage = maxRetries == Integer.MAX_VALUE
                    ? "unlimited"
                    : String.valueOf(maxRetries + 1);

                logger.warn("Transient failure on attempt {} (max: {}): {}. Retrying in {}ms...",
                        attempt + 1, retryMessage, e.getMessage(), delay);

                pauseMillis(delay);

                attempt++;

                // Only check max retries if it's not set to "infinite"
                if (maxRetries != Integer.MAX_VALUE && attempt > maxRetries) {
                    String message = String.format("Failed after %d retries: %s", maxRetries, e.getMessage());
                    logger.error(message, e);
                    throw new RuntimeException(message, e);
                }
            }
        }
    }

    /**
     * Check if an exception represents a transient error that should be retried.
     *
     * @param e the exception to check
     * @return true if the error is transient (network/timeout), false for permanent errors
     */
    private static boolean isTransientError(Exception e) {
        // Direct transient errors
        if (e instanceof SocketTimeoutException) {
            return true;
        }
        if (e instanceof IOException) {
            // IO errors are generally transient (connection reset, etc.)
            return true;
        }

        // Check for wrapped transient errors
        if (e instanceof RuntimeException && e.getCause() != null) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException || cause instanceof IOException) {
                return true;
            }
            // Check for timeout in exception message (catches various timeout types)
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                return true;
            }
        }

        // Non-transient errors (validation, programming errors, etc.)
        return false;
    }
}
