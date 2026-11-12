package com.example.practice.jobs;

import java.time.Instant;

/**
 * Handed to a worker by {@link JobService#claim}. The {@code leaseId} identifies this particular claim:
 * once the lease expires and the job is re-claimed, the old leaseId is rejected by renew/complete.
 */
public record Lease(String leaseId, String jobId, String workerId, String type, Object payload, Instant expiresAt) {
}
