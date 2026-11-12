package com.example.practice.jobs;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter: {@code delay = random(0, min(cap, base * 2^(retryCount - 1)))}.
 * <p>
 * Exponential growth backs off quickly from a struggling dependency, the cap bounds the worst-case wait,
 * and full jitter spreads out retries of jobs that failed together so they don't retry in lockstep.
 */
public final class ExponentialBackoff implements RetryPolicy {

	public static final ExponentialBackoff DEFAULT =
			new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5), RandomGenerator.getDefault());

	private final long baseMillis;
	private final long capMillis;
	private final RandomGenerator random;

	public ExponentialBackoff(Duration base, Duration cap, RandomGenerator random) {
		this.baseMillis = base.toMillis();
		this.capMillis = cap.toMillis();
		this.random = random;
	}

	@Override
	public Duration delayFor(int retryCount) {
		int exponent = retryCount - 1;
		// base * 2^exponent > cap  <=>  base > cap >> exponent; avoids overflow for large retry counts.
		long ceiling = (exponent >= 63 || baseMillis > (capMillis >> exponent)) ? capMillis : baseMillis << exponent;
		return Duration.ofMillis(random.nextLong(ceiling + 1));
	}

}
