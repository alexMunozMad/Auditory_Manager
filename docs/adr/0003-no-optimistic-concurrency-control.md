# ADR 0003 · No optimistic concurrency control: single writer by construction

**Status:** Accepted · **Related:** A2, ADR 0001

## Context

`audit` and `audit_request` have no `version` column and the code takes no `SELECT … FOR UPDATE`
lock when transitioning a row. A reviewer coming from a typical CRUD service will expect one or the
other and wonder whether its absence is an oversight.

It is not. The question is whether two transactions can ever race to write the **same row**, and in
this design they cannot.

## Why there is no race on a single row

| Row | Who writes it | Why no second writer |
|---|---|---|
| `audit_request` out of `PENDING` | The assignment worker | ADR 0001: a single worker (or one worker per auditor-pool partition) owns assignment. `FOR UPDATE SKIP LOCKED` already stops two workers claiming the same request. |
| `audit_request` `SCHEDULED → FULFILLED` | The daily fulfilment sweep / the `AuditPublished` handler | Both run inside the assignment process. The request is already terminal-bound; nothing else transitions it. |
| `audit` lifecycle (`SCHEDULED → IN_PROGRESS → PUBLISHED`) | The worker creates it; the daily sweep advances `SCHEDULED → IN_PROGRESS`; the publication endpoint advances `IN_PROGRESS → PUBLISHED` | Each transition starts from a **named** state, never a wildcard (§01, §03). The publication endpoint only touches an `IN_PROGRESS` audit — a state the worker has already left behind. |
| `audit → DISCARDED` | The in-process `AuditRequestCancelled` handler | Runs in the same transaction as the request cancellation. Only reachable from `SCHEDULED`. |

Every mutable row has exactly one writer at a time. An optimistic `version` check guards against a
lost update between two concurrent writers of one row — a situation this design does not create.

## Where concurrency *is* real, and what guards it

Concurrency exists between transactions writing **different** rows that together violate a business
rule:

- Two requests racing to book the same auditor on the same date → `UNIQUE (auditor_id, audit_date)`.
- Two workers creating an in-flight audit for the same site → `audit_one_in_flight_per_site`.

These are arbitrated by unique indexes in the database, which see both transactions. A `version`
column sees only one row and would not help.

## Options considered

### Option 1 · Add a `version` column to both tables

Standard, familiar, and inert here: it protects a row from a second concurrent writer that never
exists. It would also invite the reader to assume multi-writer access is expected, which is the
opposite of the design.

**Rejected.** Ceremony that guards nothing and misleads.

### Option 2 · `SELECT … FOR UPDATE` on every transition

Pessimistic locking of a row that no other transaction is contending for. Pure overhead, and it
muddies the claim that assignment is serial by construction rather than by locking.

**Rejected.** Same reason.

### Option 3 · Nothing, stated explicitly *(chosen)*

No `version`, no row lock on transitions. The single-writer property is the guarantee; the unique
indexes handle the real races. Recorded here so the absence reads as a decision.

## Consequences

**Cost accepted — parallelising assignment per site.** ADR 0001's scaling path is one worker per
auditor-pool partition, which keeps each `audit_request` single-writer. But if assignment is ever
sharded so that two workers can create an `audit` for the **same site** concurrently, the floor
calculation at the previous audit's expiry (A7) loses its serial backstop. `audit_one_in_flight_per_site`
still prevents two in-flight audits, so the exposure is limited to the arithmetic — at which point a
`version` column on `audit`, or `SELECT … FOR UPDATE` on the previous audit row, must be added.

**Testing implication.** The concurrency tests assert the unique indexes catch double bookings and
concurrent in-flight audits. There is no lost-update test because there is no code path that could
produce one.
