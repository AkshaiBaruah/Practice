package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobServiceTest {

	private static final Duration LEASE = Duration.ofSeconds(30);

	/** Deterministic backoff: 10s per retry. */
	private static final RetryPolicy TEN_SECONDS_PER_RETRY = n -> Duration.ofSeconds(10L * n);

	private final MutableClock clock = new MutableClock();
	private final JobService service = new JobService(clock);

	@BeforeEach
	void registerWorkers() {
		service.registerWorker("w1", Set.of("email", "image"));
		service.registerWorker("w2", Set.of("email"));
	}

	@Test
	void claimsHighestPriorityFirstAndFifoWithinPriority() {
		String low = service.submit(new JobSpec("email", null, 1));
		String highA = service.submit(new JobSpec("email", null, 9));
		String mid = service.submit(new JobSpec("image", null, 5));
		String highB = service.submit(new JobSpec("email", null, 9));

		assertThat(claimAndSucceed("w1")).isEqualTo(highA);
		assertThat(claimAndSucceed("w1")).isEqualTo(highB);
		assertThat(claimAndSucceed("w1")).isEqualTo(mid);
		assertThat(claimAndSucceed("w1")).isEqualTo(low);
		assertThat(service.claim("w1", LEASE)).isEmpty();
	}

	@Test
	void workerOnlyReceivesItsRegisteredTypes() {
		service.submit(new JobSpec("image", null));

		assertThat(service.claim("w2", LEASE)).isEmpty();
		assertThat(service.claim("w1", LEASE)).isPresent();
	}

	@Test
	void workerCanHoldOnlyOneLeaseAtATime() {
		service.submit(new JobSpec("email", null));
		service.submit(new JobSpec("email", null));
		service.claim("w1", LEASE);

		assertThatIllegalStateException().isThrownBy(() -> service.claim("w1", LEASE));
	}

	@Test
	void successMarksJobSucceeded() {
		String jobId = service.submit(new JobSpec("email", null));
		Lease lease = service.claim("w1", LEASE).orElseThrow();

		assertThat(service.complete(lease.leaseId(), new Result.Success())).isTrue();
		assertThat(service.getJobStatus(jobId)).isEqualTo(JobStatus.SUCCEEDED);
	}

	@Test
	void transientFailureRetriesAfterBackoff() {
		String jobId = service.submit(new JobSpec("email", null, 5, 3, TEN_SECONDS_PER_RETRY));

		failTransiently("w1");
		Job job = service.getJob(jobId);
		assertThat(job.status()).isEqualTo(JobStatus.RETRY_SCHEDULED);
		assertThat(job.retryCount()).isEqualTo(1);
		assertThat(job.lastError()).isEqualTo("boom");

		clock.advance(Duration.ofSeconds(9));
		assertThat(service.claim("w1", LEASE)).as("not claimable during backoff").isEmpty();

		clock.advance(Duration.ofSeconds(1));
		assertThat(service.getJobStatus(jobId)).isEqualTo(JobStatus.PENDING);

		// Second retry backs off for 20s.
		failTransiently("w1");
		clock.advance(Duration.ofSeconds(19));
		assertThat(service.claim("w1", LEASE)).isEmpty();
		clock.advance(Duration.ofSeconds(1));
		assertThat(service.claim("w1", LEASE)).map(Lease::jobId).contains(jobId);
	}

	@Test
	void movesToDlqAfterMaxRetriesExhausted() {
		String jobId = service.submit(new JobSpec("email", "p", 5, 2, TEN_SECONDS_PER_RETRY));

		for (int attempt = 1; attempt <= 3; attempt++) {
			failTransiently("w1");
			clock.advance(Duration.ofMinutes(1));
		}

		Job job = service.getJob(jobId);
		assertThat(job.status()).isEqualTo(JobStatus.FAILED);
		assertThat(job.retryCount()).isEqualTo(3);
		assertThat(service.dlqSize()).isEqualTo(1);
		assertThat(service.listDlq().get(0).job().id()).isEqualTo(jobId);
		assertThat(service.listDlq().get(0).reason()).isEqualTo("boom");
		assertThat(service.claim("w1", LEASE)).isEmpty();
	}

	@Test
	void permanentFailureSkipsRetriesAndGoesStraightToDlq() {
		String jobId = service.submit(new JobSpec("email", null, 5, 3, TEN_SECONDS_PER_RETRY));
		Lease lease = service.claim("w1", LEASE).orElseThrow();

		service.complete(lease.leaseId(), new Result.PermanentFailure("bad payload"));

		Job job = service.getJob(jobId);
		assertThat(job.status()).isEqualTo(JobStatus.FAILED);
		assertThat(job.retryCount()).isZero();
		assertThat(service.listDlq()).singleElement().satisfies(entry -> {
			assertThat(entry.job().id()).isEqualTo(jobId);
			assertThat(entry.reason()).isEqualTo("bad payload");
		});
	}

	private String claimAndSucceed(String workerId) {
		Lease lease = service.claim(workerId, LEASE).orElseThrow();
		service.complete(lease.leaseId(), new Result.Success());
		return lease.jobId();
	}

	private void failTransiently(String workerId) {
		Lease lease = service.claim(workerId, LEASE).orElseThrow();
		service.complete(lease.leaseId(), new Result.TransientFailure("boom"));
	}

}
