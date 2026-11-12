package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JobObservabilityTest {

	private static final Duration LEASE = Duration.ofSeconds(30);

	private final MutableClock clock = new MutableClock();
	private final JobService service = new JobService(clock);

	@Test
	void reportsQueueHealthAcrossMixedStates() {
		service.registerWorker("w1", Set.of("email", "image"));
		String urgent = service.submit(new JobSpec("email", null, 9));
		service.submit(new JobSpec("email", null, 5));
		service.submit(new JobSpec("email", null, 5));
		service.submit(new JobSpec("image", null, 1));
		service.submit(new JobSpec("image", null, 5, 3, n -> Duration.ofMinutes(1)));

		// Claim the urgent email job: it leaves the pending counts and becomes in-flight.
		Lease lease = service.claim("w1", LEASE).orElseThrow();
		assertThat(lease.jobId()).isEqualTo(urgent);
		assertThat(service.pendingCountByType()).isEqualTo(Map.of("email", 2, "image", 2));
		assertThat(service.pendingCountByPriority()).isEqualTo(Map.of(5, 3, 1, 1));
		assertThat(service.inFlightCount()).isEqualTo(1);
		assertThat(service.workerStats("w1")).isEqualTo(new WorkerStats(java.util.Optional.of(urgent), 0, 0));

		service.complete(lease.leaseId(), new Result.PermanentFailure("bad address"));
		assertThat(service.inFlightCount()).isZero();
		assertThat(service.dlqSize()).isEqualTo(1);
		assertThat(service.listDlq().get(0).job().id()).isEqualTo(urgent);

		// A success, then a transient failure whose retry is not counted as pending.
		service.complete(service.claim("w1", LEASE).orElseThrow().leaseId(), new Result.Success());
		service.complete(service.claim("w1", LEASE).orElseThrow().leaseId(), new Result.Success());
		service.complete(service.claim("w1", LEASE).orElseThrow().leaseId(), new Result.TransientFailure("timeout"));
		assertThat(service.pendingCountByType()).isEqualTo(Map.of("image", 1));

		WorkerStats stats = service.workerStats("w1");
		assertThat(stats.currentJobId()).isEmpty();
		assertThat(stats.successCount()).isEqualTo(2);
		assertThat(stats.failureCount()).isEqualTo(2);
	}

}
