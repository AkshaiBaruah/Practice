package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs real worker threads against the real clock. */
class WorkerTest {

	private static final Duration LEASE = Duration.ofMillis(200);
	private static final Duration POLL = Duration.ofMillis(10);

	private final JobService service = new JobService(Clock.systemUTC());
	private Worker worker;

	@AfterEach
	void stopWorker() throws InterruptedException {
		worker.stop();
	}

	@Test
	void heartbeatKeepsLongRunningJobLeased() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		JobHandler slow = payload -> {
			runs.incrementAndGet();
			Thread.sleep(LEASE.toMillis() * 3);
			return new Result.Success();
		};
		worker = new Worker("w1", Map.of("slow", slow), service, LEASE, POLL);
		String jobId = service.submit(new JobSpec("slow", null));

		worker.start();
		awaitStatus(jobId, JobStatus.SUCCEEDED);

		assertThat(service.getJob(jobId).retryCount()).isZero();
		assertThat(runs).hasValue(1);
	}

	@Test
	void handlerExceptionIsRetried() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		JobHandler flaky = payload -> {
			if (runs.incrementAndGet() == 1) {
				throw new IllegalStateException("flaky");
			}
			return new Result.Success();
		};
		worker = new Worker("w1", Map.of("flaky", flaky), service, LEASE, POLL);
		String jobId = service.submit(new JobSpec("flaky", "p", 5, 3, n -> Duration.ZERO));

		worker.start();
		awaitStatus(jobId, JobStatus.SUCCEEDED);

		assertThat(service.getJob(jobId).retryCount()).isEqualTo(1);
		assertThat(runs).hasValue(2);
		assertThat(service.workerStats("w1").failureCount()).isEqualTo(1);
	}

	private void awaitStatus(String jobId, JobStatus expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (service.getJobStatus(jobId) != expected && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(service.getJobStatus(jobId)).isEqualTo(expected);
	}

}
