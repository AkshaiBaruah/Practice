package com.example.practice.jobs;

import java.time.Instant;

/**
 * Read-only snapshot of a job's state at the time it was queried.
 * {@code availableAt} is set only while RETRY_SCHEDULED; the lease fields only while CLAIMED.
 */
public record Job(
		String id,
		JobSpec spec,
		JobStatus status,
		int retryCount,
		Instant availableAt,
		String leaseId,
		String workerId,
		Instant leaseExpiresAt,
		String lastError) {
}
