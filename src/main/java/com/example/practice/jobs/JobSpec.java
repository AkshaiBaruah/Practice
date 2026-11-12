package com.example.practice.jobs;

/**
 * What a producer submits. {@code payload} is opaque to the service and handed to the handler as-is.
 */
public record JobSpec(String type, Object payload, int priority, int maxRetries, RetryPolicy retryPolicy) {

	public static final int DEFAULT_PRIORITY = 5;
	public static final int DEFAULT_MAX_RETRIES = 3;

	public JobSpec {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type must not be blank");
		}
		if (priority < 0 || priority > 9) {
			throw new IllegalArgumentException("priority must be 0-9, was " + priority);
		}
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
		}
		if (retryPolicy == null) {
			retryPolicy = ExponentialBackoff.DEFAULT;
		}
	}

	public JobSpec(String type, Object payload) {
		this(type, payload, DEFAULT_PRIORITY, DEFAULT_MAX_RETRIES, null);
	}

	public JobSpec(String type, Object payload, int priority) {
		this(type, payload, priority, DEFAULT_MAX_RETRIES, null);
	}

}
