# ADR 0001 · Auditor assignment is asynchronous, driven by a durable request table

**Status:** Accepted · **Related:** A2, A4, A7

## Context

`POST /audit-requests` must eventually produce an auditor assignment satisfying three constraints
at once: one auditor per site per day, proportional distribution across auditors, and an audit date
inside the subscription window (A1). Requests arrive concurrently and compete for the same auditor
on the same date.

Proportional distribution is what makes this harder than a booking problem: fairness depends on a
**workload counter every concurrent assignment reads and writes** — a contention point by
construction.

## Options considered

### Option 1 · Synchronous assignment inside the request transaction

- Concurrent requests contend on the same auditor rows and the same workload counter
- Under contention, throughput collapses to serial anyway — with the client waiting for it
- Conflict retries happen inside the request, inflating tail latency unpredictably
- No place to retry a request that has no date today but may have one next month

**Rejected.** Pays the full cost of serialisation and gains none of its benefits.

### Option 2 · In-process event listener, persist after handling

- If the process dies between the `201` and the handler completing, **the request is lost**
- The client believes it was accepted; nothing records that it ever was
- Nothing to retry from — the retry source was never persisted
- Breaks A8: a business fact was acknowledged and left no trail

**Rejected.** The option that looks simplest and fails hardest.

### Option 3 · Persist, publish to a broker, assign in a consumer

Correct and durable. Justified once assignment becomes its own service or the event needs fan-out.
For a single service it adds a broker, a relay, consumer idempotency and DLQ handling to solve a
problem the database already solves.

**Deferred.** The migration path, not the starting point.

### Option 4 · Durable request table as the queue *(chosen)*

## Decision

```
POST /audit-requests
  └─ single transaction:
       insert audit_request (status = PENDING,
                             subscription_level frozen,
                             earliest_audit_date, latest_audit_date)
       insert outbox entry
       NOTIFY assignment_pending
  └─ 201 Accepted, status = PENDING

Worker loop:
  SELECT ... FROM audit_request
   WHERE status = 'PENDING'
   ORDER BY latest_audit_date          -- earliest deadline first
   FOR UPDATE SKIP LOCKED
   LIMIT n

  1. reuse check   → valid audit for site_id with
                     available_to_client_at ≤ valid_until AND ≤ deadline?  → attach, done
  2. in-flight     → audit for site_id not yet occurred but publishing
                     after this request's ceiling?  → pull it earlier, attach
  3. otherwise     → earliest free date in window, least loaded auditor
                     window floored by previous_audit.valid_until + 1 day
  4. unique violation → next candidate
  5. no candidate     → stays PENDING, awaits a capacity event
  6. deadline passed  → UNSCHEDULABLE + event
```

Three mechanisms, each doing exactly one job:

| Mechanism | Guarantees |
|---|---|
| `audit_request` table | Durability. The request survives any process failure. |
| `FOR UPDATE SKIP LOCKED` | No two workers claim the same request. |
| `UNIQUE (auditor_id, audit_date)` | No double booking, whatever the application logic does. |

`LISTEN/NOTIFY` wakes the worker immediately; a periodic sweep is the fallback, so correctness never
depends on a notification arriving.

## Retry is condition-driven, not time-driven

A request fails to place because its window is full. The window is fixed, so **the passage of time
changes nothing** — it will still be full in five minutes. A fixed retry interval is wasted work by
construction, and choosing its value would be arbitrary.

The outcome changes only when capacity appears:

| Event | Source |
|---|---|
| `AuditSlotReleased` | Cancellation frees a committed date |
| `AuditorAvailabilityOpened` | Unavailability withdrawn, calendar extended |
| `AuditorOnboarded` | New auditor added to the pool |

Each carries `(auditor_id, date)`, so the worker queries only pending requests whose interval
contains that date — a targeted lookup, not a full rescan. A daily sweep catches missed
notifications and expires requests past their deadline.

**Deadlines are derived, not configured.** `latest_audit_date = requested_at + max_wait −
processing_duration`. Every request carries its own; no global timeout is invented.

## Consequences

**Fairness becomes trivially correct.** A single writer serialises assignments, so the workload
counter is read and written without competition. The problem stops being concurrent.

**Scaling path.** Partition the auditor pool (by region, or by qualification once A3 is lifted) and
run one worker per partition. Each partition keeps its own workload counter, so there is still no
cross-worker contention. Throughput scales with partitions; fairness stays exact within each.

**Deadline ordering is a deliberate policy.** Pending requests are processed by earliest
`latest_audit_date`, not by arrival. Premium's one-month ceiling against Essentials' four months
means FIFO would breach the tighter commitment first under load. This is why the ordering column is
the derived deadline rather than `requested_at`.

**Reuse is checked before assignment, not after.** Attaching to an existing audit (A7) costs no
auditor capacity, so it is the first branch in the loop. Under duplicate demand this is the single
largest reduction in auditor workload the system can make.

**Serialisation also protects the site calendar.** Non-overlapping validity per site (A7) is
enforced by an exclusion constraint, but *choosing* between attaching, pulling an in-flight audit
earlier and creating a new one is a read-then-decide sequence. Running assignments serially means
that decision is never made on a stale view — a second, independent reason for the single writer
beyond fairness.

**The API contract changes shape.** The client receives `PENDING` and must poll
`GET /audit-requests/{id}` or consume `AuditScheduled`. A real cost of the decision, documented in
the endpoint contract.

## Testing implication

The concurrency test asserts that N threads issuing simultaneous requests for the same window
produce zero double bookings — **and that removing the unique index makes it fail**. A safety net
never observed catching anything has not been shown to work.
