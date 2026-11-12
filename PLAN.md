## Objective

Build an in-process Background Job Service. Producers submit jobs; workers pull jobs and execute them. The service guarantees at-least-once execution, retries failed jobs with backoff, surfaces stuck jobs, and exposes observability into queue health.

## Situation

## Design

### Requirements

Submit a job - submit(job_spec) -> job_id. The spec contains:

type (string) - which kind of work this is (e.g., "send_email", "resize_image")
payload (object) - opaque data passed to the handler
priority (int 0-9, higher executes earlier; default 5)
max_retries (int, default 3)
Worker model - workers register with a list of types they can handle. They pull jobs by acquiring a lease (visibility timeout). While a job is leased, no other worker can pick it up. Workers must renew the lease for long-running jobs; if the lease expires without renewal (worker crash, network partition, slow handler), the job becomes available to another worker.

Job lifecycle - implement the state machine:

pending -> claimed -> succeeded
-> failed (retries exhausted, moved to DLQ)
-> retry_scheduled -> pending (after backoff)
Lease expiry on claimed jobs returns them to pending.

Retry policy - failed jobs are re-queued with exponential backoff (you choose the formula, defend it). After max_retries exhausted, the job moves to a Dead Letter Queue (DLQ).

Job execution - the candidate provides a JobHandler interface. Workers invoke handler.run(payload) -> Result where Result is Success | TransientFailure | PermanentFailure. PermanentFailure skips retries and goes straight to DLQ.

Observability API - methods to query:

Pending count by type and by priority
Currently leased (in-flight) count
DLQ size, with ability to list DLQ entries
Per-worker stats: current lease, success/failure counts
Tests - cover at-least-once on worker crash (lease expiry), retry behavior with backoff, lease renewal happy path, priority ordering, DLQ on max retries, permanent-failure short-circuit.

### Entities
- Job Spec  
    - id;
    - type : enum
  - payload : object
  - priority : int
  - max_retries : int
  - retry_policy : RetryPolicy (default EXPONENTIAL)

- Job
  - id: string;
  - JobSpecId : string
  - retryCount : int;
  - Optional<leaseExpiryTime> : timestamp;
  - JobStatus : enum (pending, claimed, succeeded, failed, retry_scheduled)
    


- RetryPolicy
  - Type : enum (EXPONENTIAL, LINEAR)
  - backoff : int
  - jitter : int 

- JobHandler
    - Map<String, Map<Integer, BlockingQueue<Job>>> jobQueues;    // Map<JobType, Map<Priority, Queue<Job>>>
    - Map<String, Map<String, TreeSet<Job>>> workerInFlightJobs; 
    - List<Job> DLQ;
    - registerWorker(workerId, JobType, Priority) -> boolean
    - submit(job_spec) -> job_id
    - getJob(job_id) -> job_spec
    - getJobStatus(job_id) -> JobStatus
    - poll(jobType, Priority)


- JobLifecycle (enum) PENDING, IN_PROCESS, PROCESSED, RETRY_SCHEDULED, FAILED.

### High Level Flow
- submit -> create the job spec and enqueue it into the jobQueue
- registerWorker -> register the worker and add it to the worker map
- poll -> Scans the jobQueue in decreasing order of priority and polls the first job available and adds in the workerInFlightJobs map;



### Edge cases/ Special cases
- 

## Action

### Implementation Spec (pending sign-off)

Decisions confirmed: pull API + Worker loop, plain Java library (no REST), every re-enqueue increments retryCount (including lease expiry), Exponential and Linear retry policies.

Package: `com.example.practice.jobs`. Plain Java 17 + JUnit 5, no Spring beans.

#### Deviations from the Entities section above (please confirm)
1. **Service vs handler split.** The plan's `JobHandler` holds both queues and execution. Split it into `JobService` (queues, leases, DLQ, stats) and `JobHandler` (user code: `Result run(Object payload)`), matching the requirement text.
2. **One status enum.** `JobStatus` and `JobLifecycle` duplicate each other. Keep only `JobStatus { PENDING, CLAIMED, SUCCEEDED, RETRY_SCHEDULED, FAILED }`.
3. **`type` is a String**, not an enum, because the requirement says types are arbitrary strings (`"send_email"`) supplied by producers.
4. **Job embeds its spec.** `Job` holds the `JobSpec` directly instead of a `JobSpecId` lookup.
5. **Data structures.** Use one lock guarding plain collections instead of `BlockingQueue`s. Per-type queues become a `PriorityQueue` ordered by (priority desc, submission seq asc), which replaces `Map<Priority, Queue>`. Claims must atomically pick the best job across several types and update lease and stat state together, which per-queue blocking can't give.

#### Types
- `JobSpec` (record): `type`, `payload`, `priority` (0-9, default 5), `maxRetries` (≥0, default 3), `retryPolicy` (default exponential). Validated in the constructor.
- `Job`: `id`, `spec`, `status`, `retryCount`, `availableAt` (for RETRY_SCHEDULED), `lease` (`leaseId`, `workerId`, `expiresAt`), `lastError`, `seq`. `getJob` returns a read-only snapshot.
- `Result` (sealed): `Success`, `TransientFailure(reason)`, `PermanentFailure(reason)`.
- `JobHandler`: `Result run(Object payload)`. If a handler throws, the Worker treats it as a `TransientFailure`.
- `RetryPolicy` (interface): `Duration delayFor(int retryCount)`.
  - `ExponentialBackoff(base, cap)`: `delay = random(0, min(cap, base * 2^(retryCount-1)))`, which is "full jitter". Defaults are base = 1s and cap = 5min. Why this formula: exponential growth backs off fast from a struggling dependency, the cap bounds the worst-case wait, and full jitter spreads retries out so failed jobs don't all retry at once.
  - `LinearBackoff(step, cap)`: `delay = random(0, min(cap, step * retryCount))`.
  - Both take an injectable `Random` so tests are deterministic.
- `DlqEntry` (record): job snapshot, reason, failedAt.
- `WorkerStats` (record): `currentLease` (Optional jobId), `successCount`, `failureCount`.

#### JobService API (all methods are thread-safe and use an injected `java.time.Clock`)
- `submit(JobSpec) -> String jobId` puts the job in PENDING.
- `registerWorker(workerId, Set<String> types)`.
- `claim(workerId, Duration leaseDuration) -> Optional<Lease>` picks the highest-priority PENDING job across the worker's types, FIFO within a priority. The job becomes CLAIMED with a fresh `leaseId`. A worker holds **at most one lease at a time**, because the spec says "current lease", singular.
- `renew(leaseId, Duration) -> boolean` returns false if the lease is no longer current (expired or reclaimed).
- `complete(leaseId, Result) -> boolean` returns false and does nothing if the lease is stale. This is how at-least-once works: a crashed or slow worker's late result is discarded once the job has been re-leased.
- `getJob(jobId)`, `getJobStatus(jobId)`.
- `reap()` returns expired leases to the queue and promotes due RETRY_SCHEDULED jobs to PENDING. It runs automatically at the start of every claim, observability call, and complete, so no background thread is needed. It is also public so tests can call it.

#### State transitions and retry counting
- Success: CLAIMED → SUCCEEDED.
- PermanentFailure: CLAIMED → FAILED → DLQ, with retries skipped.
- TransientFailure: `retryCount++`.
  - If `retryCount > maxRetries`, FAILED → DLQ.
  - Otherwise RETRY_SCHEDULED, with `availableAt = now + policy.delayFor(retryCount)`, then PENDING once due.
- Lease expiry: `retryCount++`.
  - If `retryCount > maxRetries`, FAILED → DLQ. This keeps a job that crashes workers from looping forever.
  - Otherwise straight to PENDING with no backoff, as the requirement says.
- So `maxRetries = 3` means up to 3 re-enqueues, i.e. **4 total attempts**.

#### Observability
`pendingCountByType()`, `pendingCountByPriority()`, `inFlightCount()`, `dlqSize()`, `listDlq()`, `workerStats(workerId)`.
- Pending counts include only PENDING jobs. RETRY_SCHEDULED jobs are not counted.
- `failureCount` covers transient and permanent results the worker reported. Lease expiries are not counted, because the worker never reported them.

#### Worker
`Worker(id, Map<String, JobHandler> handlersByType, JobService, leaseDuration, idlePollInterval)`, with `start()` and `stop()`.
- On start it registers its types with the service. Then it loops on one thread: claim → run handler → complete. When there's no job, it sleeps `idlePollInterval`.
- While a handler is running, a heartbeat on a scheduled executor renews the lease every `leaseDuration / 3`.

### Tasks
Each task is a separate commit-sized step and ends with `./mvnw verify` passing.

1. **Domain types**: `JobSpec`, `JobStatus`, `Job`, `Result`, `JobHandler`, `DlqEntry`, `WorkerStats`, `Lease`. Verify: tests for JobSpec validation (priority range, defaults).
2. **Retry policies**: `ExponentialBackoff`, `LinearBackoff`. Verify: tests for the delay curve, cap, and jitter bounds with a seeded Random.
3. **JobService core**: submit, registerWorker, claim with priority ordering, complete for each Result kind, DLQ. Verify tests:
   - priority ordering and FIFO within a priority
   - permanent-failure short-circuit
   - DLQ after max retries
   - retry-with-backoff timing using a test `MutableClock`
4. **Leases**: renew, reap on expiry, stale-lease rejection. Verify tests:
   - at-least-once on worker crash (lease expires, a second worker claims the job, the first worker's late `complete` is rejected)
   - lease renewal happy path
   - repeated lease expiry ends in the DLQ
5. **Observability**: the query methods. Verify: tests for counts across mixed job states and for worker stats.
6. **Worker loop + heartbeat**. Verify: an integration test with a real clock, where a slow handler (longer than leaseDuration) completes without being reclaimed, plus a handler-exception → retry test.


## Output
1. Create a implementation spec and break the spec into independently implemtable tasks
2. Once the implementation spec is signed off, implement the entities and flow defined above;
