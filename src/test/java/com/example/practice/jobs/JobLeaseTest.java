package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobLeaseTest {

	private static final Duration LEASE = Duration.ofSeconds(30);

	private final MutableClock clock = new MutableClock();
	private final JobService service = new JobService(clock);

	@BeforeEach
	void registerWorkers() {
		service.registerWorker("w1", Set.of("email"));
		service.registerWorker("w2", Set.of("email"));
	}

	@Test
	void jobIsRedeliveredWhenWorkerCrashes() {
		String jobId = service.submit(new JobSpec("email", null));
		Lease crashed = service.claim("w1", LEASE).orElseThrow();

		clock.advance(Duration.ofSeconds(29));
		assertThat(service.claim("w2", LEASE)).as("still leased to w1").isEmpty();

		clock.advance(Duration.ofSeconds(1));
		Lease retry = service.claim("w2", LEASE).orElseThrow();
		assertThat(retry.jobId()).isEqualTo(jobId);
		assertThat(service.getJob(jobId).retryCount()).isEqualTo(1);
		assertThat(service.workerStats("w1").currentJobId()).isEmpty();

		// The crashed worker's late result is discarded; the new holder's result wins.
		assertThat(service.complete(crashed.leaseId(), new Result.PermanentFailure("late"))).isFalse();
		assertThat(service.renew(crashed.leaseId(), LEASE)).isFalse();
		assertThat(service.complete(retry.leaseId(), new Result.Success())).isTrue();
		assertThat(service.getJobStatus(jobId)).isEqualTo(JobStatus.SUCCEEDED);
		assertThat(service.dlqSize()).isZero();
	}

	@Test
	void renewedLeaseIsNotReclaimed() {
		String jobId = service.submit(new JobSpec("email", null));
		Lease lease = service.claim("w1", LEASE).orElseThrow();

		clock.advance(Duration.ofSeconds(20));
		assertThat(service.renew(lease.leaseId(), LEASE)).isTrue();
		clock.advance(Duration.ofSeconds(20)); // 40s after claim, 20s after renewal

		assertThat(service.claim("w2", LEASE)).isEmpty();
		assertThat(service.inFlightCount()).isEqualTo(1);
		assertThat(service.complete(lease.leaseId(), new Result.Success())).isTrue();
		Job job = service.getJob(jobId);
		assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
		assertThat(job.retryCount()).isZero();
	}

	@Test
	void repeatedLeaseExpiryEventuallyMovesJobToDlq() {
		String jobId = service.submit(new JobSpec("email", null, 5, 1, null));

		service.claim("w1", LEASE);
		clock.advance(LEASE);
		assertThat(service.getJobStatus(jobId)).isEqualTo(JobStatus.PENDING);

		service.claim("w1", LEASE);
		clock.advance(LEASE);

		Job job = service.getJob(jobId);
		assertThat(job.status()).isEqualTo(JobStatus.FAILED);
		assertThat(job.retryCount()).isEqualTo(2);
		assertThat(service.listDlq()).singleElement()
			.satisfies(entry -> assertThat(entry.reason()).isEqualTo("lease expired"));
	}

}
