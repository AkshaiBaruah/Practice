package com.example.practice.jobs;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Linear backoff with full jitter: {@code delay = random(0, min(cap, step * retryCount))}. */
public final class LinearBackoff implements RetryPolicy {

	private final long stepMillis;
	private final long capMillis;
	private final RandomGenerator random;

	public LinearBackoff(Duration step, Duration cap, RandomGenerator random) {
		this.stepMillis = step.toMillis();
		this.capMillis = cap.toMillis();
		this.random = random;
	}

	@Override
	public Duration delayFor(int retryCount) {
		long ceiling = (stepMillis > capMillis / retryCount) ? capMillis : stepMillis * retryCount;
		return Duration.ofMillis(random.nextLong(ceiling + 1));
	}

}
