package com.example.practice.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In-process job queue with leases, retries and a dead-letter queue.
 * <p>
 * All state is guarded by this object's monitor so a claim can atomically pick the best job across
 * several types and update lease and worker state together. There is no background thread: expired
 * leases and due retries are processed by {@link #reap()}, which every public method runs first.
 */
public final class JobService {

	/** Higher priority first; FIFO (submission order) within a priority. */
	private static final Comparator<Entry> CLAIM_ORDER =
			Comparator.<Entry>comparingInt(e -> -e.spec.priority()).thenComparingLong(e -> e.seq);

	private final Clock clock;
	private final Map<String, Entry> jobs = new HashMap<>();
	private final Map<String, PriorityQueue<Entry>> pendingByType = new HashMap<>();
	private final PriorityQueue<Entry> retryScheduled = new PriorityQueue<>(Comparator.comparing(e -> e.availableAt));
	private final Map<String, Entry> leases = new HashMap<>();
	private final Map<String, WorkerState> workers = new HashMap<>();
	private final List<DlqEntry> dlq = new ArrayList<>();
	private long nextSeq;

	public JobService(Clock clock) {
		this.clock = clock;
	}

	public synchronized String submit(JobSpec spec) {
		Entry entry = new Entry(UUID.randomUUID().toString(), spec, nextSeq++);
		jobs.put(entry.id, entry);
		enqueuePending(entry);
		return entry.id;
	}

	/** Registers (or re-registers with new types) a worker. Stats survive re-registration. */
	public synchronized void registerWorker(String workerId, Set<String> types) {
		workers.computeIfAbsent(workerId, id -> new WorkerState()).types = Set.copyOf(types);
	}

	/**
	 * Leases the highest-priority pending job among the worker's types.
	 *
	 * @throws IllegalStateException if the worker already holds a live lease
	 */
	public synchronized Optional<Lease> claim(String workerId, Duration leaseDuration) {
		reap();
		WorkerState worker = worker(workerId);
		if (worker.leaseId != null) {
			throw new IllegalStateException("worker " + workerId + " already holds lease " + worker.leaseId);
		}
		PriorityQueue<Entry> best = null;
		for (String type : worker.types) {
			PriorityQueue<Entry> queue = pendingByType.get(type);
			if (queue != null && !queue.isEmpty()
					&& (best == null || CLAIM_ORDER.compare(queue.peek(), best.peek()) < 0)) {
				best = queue;
			}
		}
		if (best == null) {
			return Optional.empty();
		}
		Entry entry = best.poll();
		entry.status = JobStatus.CLAIMED;
		entry.leaseId = UUID.randomUUID().toString();
		entry.workerId = workerId;
		entry.leaseExpiresAt = clock.instant().plus(leaseDuration);
		leases.put(entry.leaseId, entry);
		worker.leaseId = entry.leaseId;
		return Optional.of(new Lease(entry.leaseId, entry.id, workerId, entry.spec.type(), entry.spec.payload(),
				entry.leaseExpiresAt));
	}

	/** Extends a live lease to now + leaseDuration. Returns false if the lease has expired or was re-claimed. */
	public synchronized boolean renew(String leaseId, Duration leaseDuration) {
		reap();
		Entry entry = leases.get(leaseId);
		if (entry == null) {
			return false;
		}
		entry.leaseExpiresAt = clock.instant().plus(leaseDuration);
		return true;
	}

	/**
	 * Reports the outcome of a leased job. Returns false, and changes nothing, if the lease is no longer
	 * live: the job has already been handed to another worker, so this late result is discarded.
	 */
	public synchronized boolean complete(String leaseId, Result result) {
		reap();
		Entry entry = leases.remove(leaseId);
		if (entry == null) {
			return false;
		}
		WorkerState worker = workers.get(entry.workerId);
		worker.leaseId = null;
		clearLease(entry);
		if (result instanceof Result.Success) {
			entry.status = JobStatus.SUCCEEDED;
			worker.successCount++;
		}
		else if (result instanceof Result.PermanentFailure failure) {
			worker.failureCount++;
			entry.lastError = failure.reason();
			moveToDlq(entry);
		}
		else if (result instanceof Result.TransientFailure failure) {
			worker.failureCount++;
			requeue(entry, failure.reason(), true);
		}
		else {
			throw new IllegalArgumentException("unknown result: " + result);
		}
		return true;
	}

	/** Returns expired leases to the queue and promotes retries whose backoff has elapsed. */
	public synchronized void reap() {
		Instant now = clock.instant();
		Iterator<Entry> it = leases.values().iterator();
		while (it.hasNext()) {
			Entry entry = it.next();
			if (!now.isBefore(entry.leaseExpiresAt)) {
				it.remove();
				workers.get(entry.workerId).leaseId = null;
				clearLease(entry);
				requeue(entry, "lease expired", false);
			}
		}
		while (!retryScheduled.isEmpty() && !retryScheduled.peek().availableAt.isAfter(now)) {
			Entry entry = retryScheduled.poll();
			entry.availableAt = null;
			enqueuePending(entry);
		}
	}

	public synchronized Job getJob(String jobId) {
		reap();
		Entry entry = jobs.get(jobId);
		if (entry == null) {
			throw new IllegalArgumentException("unknown job " + jobId);
		}
		return entry.snapshot();
	}

	public JobStatus getJobStatus(String jobId) {
		return getJob(jobId).status();
	}

	// ---- Observability ----

	public synchronized Map<String, Integer> pendingCountByType() {
		reap();
		Map<String, Integer> counts = new TreeMap<>();
		pendingByType.forEach((type, queue) -> {
			if (!queue.isEmpty()) {
				counts.put(type, queue.size());
			}
		});
		return counts;
	}

	public synchronized Map<Integer, Integer> pendingCountByPriority() {
		reap();
		Map<Integer, Integer> counts = new TreeMap<>();
		for (PriorityQueue<Entry> queue : pendingByType.values()) {
			for (Entry entry : queue) {
				counts.merge(entry.spec.priority(), 1, Integer::sum);
			}
		}
		return counts;
	}

	public synchronized int inFlightCount() {
		reap();
		return leases.size();
	}

	public synchronized int dlqSize() {
		reap();
		return dlq.size();
	}

	public synchronized List<DlqEntry> listDlq() {
		reap();
		return List.copyOf(dlq);
	}

	public synchronized WorkerStats workerStats(String workerId) {
		reap();
		WorkerState worker = worker(workerId);
		Optional<String> currentJobId = Optional.ofNullable(worker.leaseId).map(id -> leases.get(id).id);
		return new WorkerStats(currentJobId, worker.successCount, worker.failureCount);
	}

	// ---- Internals ----

	/** Every re-enqueue counts as a retry; once retries are exhausted the job goes to the DLQ instead. */
	private void requeue(Entry entry, String reason, boolean withBackoff) {
		entry.retryCount++;
		entry.lastError = reason;
		if (entry.retryCount > entry.spec.maxRetries()) {
			moveToDlq(entry);
		}
		else if (withBackoff) {
			entry.status = JobStatus.RETRY_SCHEDULED;
			entry.availableAt = clock.instant().plus(entry.spec.retryPolicy().delayFor(entry.retryCount));
			retryScheduled.add(entry);
		}
		else {
			enqueuePending(entry);
		}
	}

	private void enqueuePending(Entry entry) {
		entry.status = JobStatus.PENDING;
		pendingByType.computeIfAbsent(entry.spec.type(), t -> new PriorityQueue<>(CLAIM_ORDER)).add(entry);
	}

	private void moveToDlq(Entry entry) {
		entry.status = JobStatus.FAILED;
		dlq.add(new DlqEntry(entry.snapshot(), entry.lastError, clock.instant()));
	}

	private static void clearLease(Entry entry) {
		entry.leaseId = null;
		entry.workerId = null;
		entry.leaseExpiresAt = null;
	}

	private WorkerState worker(String workerId) {
		WorkerState worker = workers.get(workerId);
		if (worker == null) {
			throw new IllegalArgumentException("unregistered worker " + workerId);
		}
		return worker;
	}

	private static final class Entry {

		final String id;
		final JobSpec spec;
		final long seq;
		JobStatus status;
		int retryCount;
		Instant availableAt;
		String leaseId;
		String workerId;
		Instant leaseExpiresAt;
		String lastError;

		Entry(String id, JobSpec spec, long seq) {
			this.id = id;
			this.spec = spec;
			this.seq = seq;
		}

		Job snapshot() {
			return new Job(id, spec, status, retryCount, availableAt, leaseId, workerId, leaseExpiresAt, lastError);
		}

	}

	private static final class WorkerState {

		Set<String> types = new HashSet<>();
		String leaseId;
		long successCount;
		long failureCount;

	}

}
