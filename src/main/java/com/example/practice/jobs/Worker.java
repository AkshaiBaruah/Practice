package com.example.practice.jobs;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pulls jobs for its handler types on a single thread: claim, run the handler, complete. While a handler
 * runs, a heartbeat renews the lease every {@code leaseDuration / 3} so long jobs are not reclaimed.
 */
public final class Worker {

	private final String id;
	private final Map<String, JobHandler> handlersByType;
	private final JobService service;
	private final Duration leaseDuration;
	private final Duration idlePollInterval;
	private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
	private volatile boolean running;
	private Thread thread;

	public Worker(String id, Map<String, JobHandler> handlersByType, JobService service, Duration leaseDuration,
			Duration idlePollInterval) {
		this.id = id;
		this.handlersByType = Map.copyOf(handlersByType);
		this.service = service;
		this.leaseDuration = leaseDuration;
		this.idlePollInterval = idlePollInterval;
	}

	public void start() {
		service.registerWorker(id, handlersByType.keySet());
		running = true;
		thread = new Thread(this::loop, "worker-" + id);
		thread.start();
	}

	/** Stops the loop. A handler interrupted mid-run typically throws, which is reported as a transient failure. */
	public void stop() throws InterruptedException {
		running = false;
		thread.interrupt();
		thread.join();
		heartbeat.shutdownNow();
	}

	private void loop() {
		while (running) {
			Optional<Lease> lease = service.claim(id, leaseDuration);
			if (lease.isPresent()) {
				execute(lease.get());
				continue;
			}
			try {
				Thread.sleep(idlePollInterval.toMillis());
			}
			catch (InterruptedException e) {
				return;
			}
		}
	}

	private void execute(Lease lease) {
		long period = Math.max(1, leaseDuration.toMillis() / 3);
		ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(
				() -> service.renew(lease.leaseId(), leaseDuration), period, period, TimeUnit.MILLISECONDS);
		Result result;
		try {
			result = handlersByType.get(lease.type()).run(lease.payload());
		}
		catch (Exception e) {
			result = new Result.TransientFailure(e.toString());
		}
		finally {
			renewal.cancel(false);
		}
		service.complete(lease.leaseId(), result);
	}

}
