package com.example.practice.jobs;

import java.util.Optional;

/**
 * {@code currentJobId} is the job the worker currently holds a live lease on, if any.
 * {@code failureCount} counts failures the worker reported (transient + permanent), not lease expiries.
 */
public record WorkerStats(Optional<String> currentJobId, long successCount, long failureCount) {
}
