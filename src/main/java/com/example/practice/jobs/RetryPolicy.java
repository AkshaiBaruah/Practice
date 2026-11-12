package com.example.practice.jobs;

import java.time.Duration;

@FunctionalInterface
public interface RetryPolicy {

	/** Delay before the job becomes pending again. {@code retryCount} is 1 for the first retry. */
	Duration delayFor(int retryCount);

}
