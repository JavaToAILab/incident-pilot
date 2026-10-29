package io.github.javatoailab.incidentpilot.llm;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff with full jitter.
 *
 * <p>Jitter matters: without it, every client that failed at the same moment
 * retries at the same moment, and a struggling model server gets hit again in a wave.
 *
 * @param maxAttempts total attempts, including the first (1 = no retries)
 * @param baseDelay   delay ceiling before the first retry
 * @param maxDelay    upper bound on any single delay
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(8));
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    /** Delay before retry number {@code retry} (1-based): random in [0, min(max, base * 2^(retry-1))]. */
    Duration delayBeforeRetry(int retry) {
        long ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * (1L << Math.min(retry - 1, 20)));
        return ceiling <= 0 ? Duration.ZERO : Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }
}
