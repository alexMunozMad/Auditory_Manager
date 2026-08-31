# 05 · Concurrency

The centre of the design. Everything else — the two-table split, the outbox, the frozen columns —
is arranged so that this section has a short answer.

The rationale for the two decisions this rests on is in the ADRs; this document is the single
narrative that ties them together.

---

## 1 · The scenario

Several clients request an audit of different sites in the same week. Their delivery windows overlap.
The assignment logic must, for each request, find a free date for the **least-loaded eligible
auditor** — and two requests being placed at the same instant can both see the same auditor free on
the same day, and both see that auditor as least loaded.

Three things race:

| Race | Two transactions… |
|---|---|
| **Auditor calendar** | pick the same `(auditor, date)` |
| **Site calendar** | create a second in-flight audit for a site that already has one |
| **Fairness counter** | read a workload figure the other is about to change |

The fairness counter is the hard one. Proportional distribution means every assignment reads a
number that every other assignment writes — a contention point **by construction**, not by
accident.

---

## 2 · The move: turn a concurrency problem into a scheduling problem

Assignment does not happen in the request's transaction. `POST /v1/audit-requests` persists the
request as `PENDING` and returns `201` (ADR 0001). A **single background worker** performs
assignment, reading from the `audit_request` table — which *is* the queue.

With one writer, assignments **serialise**. The fairness counter is read and written without
competition because there is no second reader. The race in §1 row 3 disappears — not mitigated,
removed. What is left is a scheduling problem: walk the pending queue by deadline, place each one.

This is not for latency. Moving assignment off the request path is what buys the control over
parallelism that makes proportional distribution correct without locks.

---

## 3 · Mechanisms considered

Five ways to arbitrate the auditor-calendar race. One was chosen; the rest are recorded so the
choice is legible.

### Synchronous assignment inside the request transaction

Concurrent requests contend on the same auditor rows and the same workload counter. Under contention
throughput collapses to serial anyway — with the client holding the connection open for it. Conflict
retries inflate tail latency unpredictably, and there is nowhere to park a request that has no date
today but may have one next month.

**Rejected.** Pays the full cost of serialisation and gains none of its benefits. Full analysis:
ADR 0001.

### Optimistic lock — a `version` column on the auditor calendar

Standard, and inert here. It guards one row against a second concurrent writer, and the single
writer means no row has two writers. The real races are **cross-row** — two transactions writing
different `audit` rows that together break a rule — and a `version` sees only its own row.

**Rejected.** Ceremony that guards a race the design already removed, and misleads the reader into
thinking multi-writer access is expected. Full analysis: ADR 0003.

### Advisory locks to serialise per auditor

`pg_advisory_xact_lock(auditor_id)` around each placement. It works, but it serialises the hot path
with a lock whose lifetime you now manage, to protect something the durable queue plus the unique
constraint already protect — and the request still has to be persisted first, so the queue does not
go away. It also serialises *per auditor*, which is finer than needed: the single worker already
serialises everything, and when that becomes the bottleneck the answer is partitioning (§7), not
lock granularity.

**Rejected.** A lock to re-implement, badly, a guarantee two cheaper mechanisms already give.

### `SELECT … FOR UPDATE SKIP LOCKED` on auditor slots

There is no slot table to lock. Availability is **computed** — a date is free for an auditor when no
`audit` row exists for that pair (A1). Materialising slots to lock them would be a table generated
purely to be contended on.

**Adapted, not rejected.** `SKIP LOCKED` is used — on the `audit_request` queue, so two workers (if
ever there are two, §7) never claim the same *request*. Not on slots.

### Command queue, one consumer per partition

**Chosen, in its minimal form.** The `audit_request` table is the queue; the worker is the single
consumer. `LISTEN/NOTIFY` wakes it; a periodic sweep is the fallback so correctness never depends on
a notification arriving. Partitioning the consumer is the scaling path (§7), not the starting point.

---

## 4 · The chosen design — three mechanisms, one job each

| Mechanism | Guarantees | Where |
|---|---|---|
| `audit_request` table | Durability. The request survives any process failure; there is always something to retry from. | ADR 0001 |
| `FOR UPDATE SKIP LOCKED` on the claim query | No two workers claim the same pending request. | ADR 0001 |
| `UNIQUE (auditor_id, audit_date)` | No double booking — whatever the application logic does. | 02 §5 |
| `UNIQUE (site_id) WHERE status IN ('SCHEDULED','IN_PROGRESS')` | At most one in-flight audit per site — the real site-calendar race. | 02 §5 |

**Deliberate duplication.** The worker also checks availability in code before choosing a candidate
— not for correctness, which the constraint owns, but so the ordinary case returns a clear error
instead of a driver-level constraint violation. One layer is ergonomics, the other is correctness
(01 §3).

The worker loop, per claimed request:

```
1. reuse check → two reads for the site (02 §6):
     - the one possible in-flight audit (index 2); validity projected
     - the current published audit (index 3); real valid_until
   either satisfies  available_to_client_at ≤ validity  AND  ≤ deadline?  → attach, done
2. otherwise    → earliest free date in the window, least-loaded auditor;
                  window floored at previous_audit.valid_until + 1 day
3. unique violation → next candidate
4. no candidate     → stays PENDING, awaits a capacity event (§6)
5. deadline passed  → UNSCHEDULABLE + event
```

---

## 5 · Fairness under concurrency

Least-loaded eligible auditor over a rolling 90-day window, ties broken by auditor id for
reproducibility (A2). Behind `AuditorSelectionPolicy`, so weighting by duration, specialty or region
is an implementation swap that does not touch anything here.

It is **correct by construction** because the single writer serialises. Three requests arrive for
the same window: the first goes to auditor A, A's computed load rises, the second goes to B, the
third to C. Balance happens **per assignment**, not by a quota — there is no cap on audits per
auditor, and there is no stored counter, because a lifetime total cannot express a rolling window
(02 §7).

The workload figure is a `COUNT` over `audit` rows in the window. The index it needs —
`UNIQUE (auditor_id, audit_date)` — already exists for the constraint. One index, two uses.

---

## 6 · Retry is condition-driven, not time-driven

A request fails to place because its window is full. The window is **fixed** (A6, A9), so the
passage of time changes nothing — it will still be full in five minutes. A fixed retry interval is
wasted work by construction, and its value would be arbitrary.

The outcome changes only when **capacity appears**:

| Event | Origin | Reaches the worker via |
|---|---|---|
| `AuditSlotReleased` | this system — a discard frees a date | `NOTIFY`, same transaction (04 §1) |
| `AuditorAvailabilityOpened` | external context | broker consumer → same handler |
| `AuditorOnboarded` | external context | broker consumer → same handler |

Each carries `(auditor_id, date)`, so the worker queries only the pending requests whose window
contains that date — a targeted lookup, not a full rescan. A daily sweep catches missed
notifications and expires requests past their deadline.

The one query no index serves well — window-containment on two columns — is acceptable because the
pending set is bounded: requests leave it as soon as they are placed (02 §6).

---

## 7 · Scaling past one worker

Partition the auditor pool — by region, or by qualification once A3 is lifted — and run **one worker
per partition**. Each partition keeps its own workload figure, so there is still no cross-worker
contention on fairness. Throughput scales with partitions; fairness stays exact within each.

`FOR UPDATE SKIP LOCKED` already makes multiple workers safe on the claim query.
`UNIQUE (auditor_id, audit_date)` still arbitrates any cross-partition collision.

**Cost accepted.** If assignment is ever sharded so two workers can create an `audit` for the **same
site** concurrently, the floor-at-previous-expiry calculation (A7) loses its serial backstop. The
partial unique index still prevents two in-flight audits, so the exposure is limited to arithmetic —
at which point a `version` column or `SELECT … FOR UPDATE` on `audit` is added (ADR 0003). Recorded
here rather than discovered later.

---

## 8 · The event leaves by transactional outbox

Every state transition writes an `outbox_event` row **in the same transaction** as the change
(A8, ADR 0002). A single relay publishes each row to the broker afterwards, which fans out to the
audit-trail service, the notification consumer and future webhooks (04 §1).

If the event were published inside the business transaction, a broker timeout would roll back a
sound assignment; if it were published by a downstream consumer after commit and the message were
lost, a booked auditor would exist with no trail of it. The outbox makes divergence impossible: the
fact and its record commit together or not at all. **The broker is a transport concern, never a
durability one.**

---

## 9 · What proves it

The concurrency test — the day-2 deliverable and the evidence shown in the defence:

- **N threads** issue simultaneous requests whose windows all contain the same dates.
- Assert: **zero double bookings**, every request either placed or left `PENDING`, the workload
  spread matches least-loaded.
- Then **remove `UNIQUE (auditor_id, audit_date)`** and assert the same test now **fails**.

A safety net never observed catching anything has not been shown to work. Testcontainers, not an
in-memory database: the design delegates two guarantees to the engine — partial unique indexes under
concurrency and `SKIP LOCKED` — and an in-memory store reproduces neither (testing strategy · `06`).

The worker's decision tree — reuse, schedule a new audit, defer, or give up — is drawn in
[`diagrams/worker-decision.mermaid`](diagrams/worker-decision.mermaid); the `UNIQUE (auditor_id,
audit_date)` branch on it is where a lost race is caught.

---

## 10 · One-paragraph version

Assignment is asynchronous and single-writer, so it serialises and the fairness counter is read
without contention — a concurrency problem becomes a scheduling one. The two real races that remain
are on the auditor calendar and the site calendar, and both are arbitrated by ordinary unique
indexes in Postgres, where application logic cannot bypass them. Retry is driven by capacity events,
not a timer, because the scheduling window is fixed. The trail leaves by transactional outbox so a
business fact and its record are atomic. It scales by partitioning the worker, not by adding locks.
