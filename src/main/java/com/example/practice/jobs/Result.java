package com.example.practice.jobs;

public sealed interface Result {

	record Success() implements Result {
	}

	/** Worth retrying (e.g. a timeout). Re-queued with backoff until maxRetries is exhausted. */
	record TransientFailure(String reason) implements Result {
	}

	/** Retrying will not help (e.g. invalid payload). Goes straight to the DLQ. */
	record PermanentFailure(String reason) implements Result {
	}

}
