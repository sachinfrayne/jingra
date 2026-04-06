package org.elasticsearch.jingra.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for RetryHelper utility that handles retry logic for transient failures.
 */
class RetryHelperTest {

    @Test
    void privateConstructor_isCallableViaReflection() throws Exception {
        Constructor<RetryHelper> ctor = RetryHelper.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    void computeRetryDelayMs_exponentialGrowthUntilAttemptCap() {
        assertEquals(10L, RetryHelper.computeRetryDelayMs(0, 10));
        assertEquals(20L, RetryHelper.computeRetryDelayMs(1, 10));
        assertEquals(640L, RetryHelper.computeRetryDelayMs(6, 10));
        assertEquals(640L, RetryHelper.computeRetryDelayMs(7, 10), "attempt > 6 keeps exponent at 2^6");
    }

    @Test
    void computeRetryDelayMs_capsAtMaxBackoff() {
        assertEquals(60_000L, RetryHelper.computeRetryDelayMs(6, 2_000),
                "64 * 2000 > MAX_BACKOFF_MS");
    }

    @Test
    void execute_succeedsOnFirstAttempt() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            attempts.incrementAndGet();
            return 42;
        };

        int result = RetryHelper.executeWithRetry(operation, 3, 100);

        assertEquals(42, result);
        assertEquals(1, attempts.get());
    }

    @Test
    void execute_retriesOnDirectSocketTimeout_thenSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                throw new SocketTimeoutException("Read timed out");
            }
            return 100;
        };

        int result = RetryHelper.executeWithRetry(operation, 5, 10);

        assertEquals(100, result);
        assertEquals(3, attempts.get());
    }

    @Test
    void execute_retriesOnDirectIOException_thenSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new IOException("Connection reset");
            }
            return 50;
        };

        int result = RetryHelper.executeWithRetry(operation, 3, 10);

        assertEquals(50, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void execute_retriesOnRuntimeExceptionWithSocketTimeoutCause() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 1) {
                throw new RuntimeException("error while performing request", new SocketTimeoutException("Timeout"));
            }
            return 75;
        };

        int result = RetryHelper.executeWithRetry(operation, 3, 10);

        assertEquals(75, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void execute_retriesOnRuntimeExceptionWithIOExceptionCause() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("wrap", new IOException("reset"));
            }
            return 3;
        };

        int result = RetryHelper.executeWithRetry(operation, 5, 5);

        assertEquals(3, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void execute_retriesWhenRuntimeHasNonIoCauseButMessageContainsTimeout() {
        AtomicInteger attempts = new AtomicInteger(0);
        int result = RetryHelper.executeWithRetry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("upstream read timeout", new IllegalStateException("not io"));
            }
            return 7;
        }, 5, 5);

        assertEquals(7, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void execute_doesNotRetryWhenRuntimeMessageContainsTimeoutButCauseIsNull() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("read timeout");
                }, 5, 5));

        assertEquals(1, attempts.get());
        assertEquals("read timeout", thrown.getMessage());
    }

    @Test
    void execute_doesNotRetryWhenRuntimeHasNonIoCauseAndNullMessage() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException root = new RuntimeException((String) null, new IllegalStateException("nested"));
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw root;
                }, 5, 5));

        assertEquals(1, attempts.get());
        assertSame(root, thrown);
    }

    @Test
    void execute_doesNotRetryWhenRuntimeHasNonIoCauseAndMessageWithoutTimeout() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    // Must not contain the substring "timeout" (case-insensitive)
                    throw new RuntimeException("permanent client error", new IllegalStateException("nested"));
                }, 5, 5));

        assertEquals(1, attempts.get());
        assertEquals("permanent client error", thrown.getMessage());
    }

    @Test
    void execute_doesNotRetryNonTransientRuntimeException() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(IllegalArgumentException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("Invalid input");
                }, 3, 10));

        assertEquals(1, attempts.get());
    }

    @Test
    void execute_doesNotRetryNullPointerException() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(NullPointerException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new NullPointerException("Null value");
                }, 3, 10));

        assertEquals(1, attempts.get());
    }

    @Test
    void execute_wrapsCheckedNonTransientExceptionWithStableMessage() {
        Exception cause = new Exception("permanent failure");
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    throw cause;
                }, 3, 10));

        assertEquals("Non-transient error during operation", thrown.getMessage());
        assertEquals(cause, thrown.getCause());
    }

    @Test
    void execute_exhaustionUsesExactMessageAndPreservesCause() {
        AtomicInteger attempts = new AtomicInteger(0);
        SocketTimeoutException root = new SocketTimeoutException("Persistent timeout");
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                RetryHelper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw root;
                }, 3, 10));

        assertEquals(4, attempts.get());
        assertEquals("Failed after 3 retries: Persistent timeout", thrown.getMessage());
        assertEquals(root, thrown.getCause());
    }

    @Test
    void execute_integerMaxValue_allowsUnboundedRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryHelper.RetryableOperation<Integer> operation = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 10) {
                throw new SocketTimeoutException("Temporary timeout");
            }
            return 42;
        };

        int result = RetryHelper.executeWithRetry(operation, Integer.MAX_VALUE, 10);

        assertEquals(42, result);
        assertEquals(11, attempts.get());
    }

    @Test
    void execute_interruptedDuringBackoff_throwsWithStableMessageAndCauseAndPreservesInterrupt() {
        AtomicInteger attempts = new AtomicInteger(0);
        Thread.currentThread().interrupt();
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    RetryHelper.executeWithRetry(() -> {
                        attempts.incrementAndGet();
                        throw new IOException("transient");
                    }, 3, 0L));

            assertEquals(1, attempts.get());
            assertEquals("Interrupted during retry backoff", thrown.getMessage());
            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear interrupt so other tests on this thread are not affected
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
    }

    @Test
    void execute_waitsBeforeRetry() {
        AtomicInteger attempts = new AtomicInteger(0);
        long[] timestamps = new long[2];

        RetryHelper.RetryableOperation<Integer> operation = () -> {
            int attempt = attempts.incrementAndGet();
            timestamps[attempt - 1] = System.currentTimeMillis();
            if (attempt == 1) {
                throw new SocketTimeoutException("Timeout");
            }
            return 1;
        };

        RetryHelper.executeWithRetry(operation, 3, 10);

        long delay = timestamps[1] - timestamps[0];
        assertTrue(delay >= 10, "Should wait at least base backoff before retry, actual: " + delay + "ms");
    }
}
