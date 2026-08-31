# 06 · Testing strategy

**The strategy is stack-agnostic** — it is about *what* is tested at *which* level and *why*. The
concrete tool names in this document (JUnit, Mockito, Testcontainers, Awaitility, JaCoCo, and the
Spring / Liquibase context they run in) are the instrumentation chosen for the step-2 slice, not
part of the design. Every other document in this repository is stack-agnostic; this one names tools
because a testing plan has to.

A real pyramid, not a diamond. Most tests are unit tests of a domain that needs almost no mocking. A
thinner band of integration tests covers exactly what only Postgres can prove. A handful of API
tests. **One concurrency test that is the whole point.**

The shape follows the design: the risky *logic* is calendar arithmetic and state transitions, which
is pure and unit-testable; the risky *infrastructure* is four constraints and `SKIP LOCKED`, which
only the real engine reproduces. A rich domain model (01) is what makes the base of the pyramid wide
— objects with behaviour and few collaborators need few mocks. If a layer needed heavy mocking, that
would be a sign the logic had leaked into services.

---

## 1 · Unit tests — the base, no Spring, no database

Plain JUnit 5. Thousands run in a second. No mocks on domain objects; Mockito appears only at the
use-case seam (§4).

| Under test | Cases |
|---|---|
| `DeliveryWindow` (01 §4, A1) | earliest/latest per level; the `max(…, tomorrow)` floor for Premium (`requested − 7` is in the past); `contains(date)`; 29/02 → 28/02 on leap years |
| `AuditRequest` window maths | `latest_audit_date = requested_at + max_wait − processing`; frozen once, never re-derived |
| Reuse eligibility (A7) | both conditions — `available_to_client_at ≤ validity` and `≤ deadline`; the effective-window difference by level |
| `available_to_client_at` | `max(published_at, requested_at + min_window)`; reconciliation to the *actual* publication date |
| `Audit` maths | `published_at = audit_date + processing`; `valid_until = published_at + 1 year`; floor at `previous_audit.valid_until + 1 day` |
| `AuditRequest` state machine | `PENDING → SCHEDULED → FULFILLED`; `PENDING → UNSCHEDULABLE`; `→ CANCELLED`; every transition starts from a **named** state, a wildcard source is rejected |
| `Audit` state machine | `SCHEDULED → IN_PROGRESS → PUBLISHED`; `→ DISCARDED` only from `SCHEDULED`, unconditional |
| Fulfilment rule | `fulfilled ⟺ audit PUBLISHED ∧ today ≥ available_to_client_at` — including `SCHEDULED` against an already-`PUBLISHED` audit |
| `AuditorSelectionPolicy` | least-loaded over the rolling window; ties broken by auditor id; the load figure is a count, never a stored counter |
| Assignment **decision** | given pending requests + existing audits (ports stubbed): attach to in-flight / attach to published / schedule new / leave `PENDING` / mark `UNSCHEDULABLE` |
| Problem-statement example | "Farma World Champion" + three subscription levels → the three audit dates the statement implies |

**Not covered here:** persistence, constraints, concurrency, HTTP. Those are integration concerns
and testing them with mocks would prove nothing.

---

## 2 · Integration tests — Spring + Testcontainers Postgres

Fewer, and each one earns its place by testing something the unit layer *cannot*: real Postgres
semantics. A single Postgres 18 container, started once and reused across the suite; Liquibase
migrates it; a SQL seed script loads the reference rows (§3).

| Under test | Assertion |
|---|---|
| `UNIQUE (auditor_id, audit_date)` | two audits for the same auditor and day — the second insert throws |
| `UNIQUE (site_id) WHERE status IN ('SCHEDULED','IN_PROGRESS')` | a second in-flight audit for a site — rejected; a second *published* audit — allowed |
| `FOR UPDATE SKIP LOCKED` claim query | two transactions claiming from the pending queue get disjoint rows |
| `CHECK` constraints | `request_attached_has_audit`, `request_scheduled_has_access_date`, `audit_published_has_report`, `request_window_starts_in_future`, `request_cancelled_has_reason` each reject the bad row |
| `UNIQUE (client_id, idempotency_key)` | same key, same client — second insert rejected |
| `updated_at` trigger | a bare `UPDATE …` (no ORM) bumps `updated_at` |
| Repository mapping | rich domain object → row → domain object round-trips, all value objects intact |
| Reuse check (02 §6) | the two reads — in-flight via index 2, published via index 3 — return the right candidate against seeded data |
| Fulfilment sweep | the query fulfils only `SCHEDULED` requests whose audit is `PUBLISHED` **and** date has passed; leaves a slipped `IN_PROGRESS` one alone |
| Outbox atomicity | a rolled-back assignment leaves **no** `outbox_event` row; a committed one leaves exactly one |
| `notification_dispatch` claim | concurrent `INSERT … ON CONFLICT (event_id) DO NOTHING` — exactly one inserts, one sends |
| Liquibase | the full changelog applies to an empty database with no error |

**Not covered here:** domain arithmetic (already unit-tested — an integration test that re-checks a
window calculation is a slow duplicate).

---

## 3 · The concurrency test — its own category

The centrepiece, and the evidence shown in the defence (05 §9). Integration-level, real Postgres.

**Setup.** Seed a pool of auditors and a batch of `PENDING` requests whose windows all contain the
same set of dates — deliberate contention for the same `(auditor, date)` slots.

**Exercise.** Start K worker threads against the shared queue.

**Assert.**
- **Zero** `(auditor_id, audit_date)` collisions in the `audit` table.
- Every request ends `SCHEDULED` or `PENDING` — never lost, never double-committed.
- The spread of assignments across auditors matches least-loaded (no auditor runs away with the
  batch).
- Exactly one `outbox_event` per state transition.

**Then prove the net catches something.** Re-run the same test against a schema variant with
`UNIQUE (auditor_id, audit_date)` dropped (an `ALTER TABLE … DROP CONSTRAINT` in a dedicated test
migration) and assert it now **fails** with double bookings. A safety net never observed catching
anything has not been shown to work.

**Why Testcontainers and not an in-memory database.** The design delegates two guarantees to the
engine — partial unique indexes under concurrency, and `SKIP LOCKED`. An in-memory store reproduces
neither, so a green test against one would be a green test of a different system. This is the
strongest single argument for the tooling choice.

Async steps (a worker picking up a `PENDING` row, the sweep fulfilling) are asserted with
**Awaitility** polling, never `Thread.sleep`.

---

## 4 · API tests — minimal, full stack

Three or four, `@SpringBootTest` with the Testcontainers Postgres and the HTTP layer live:

- `POST /v1/audit-requests` happy path → `201` with the projected commitment; `GET` returns it.
- Idempotency: same key + same body → the original `201` replayed; same key + different body →
  `422 idempotency-key-reused`.
- `GET /v1/audit-requests/{id}/report` → `409` before fulfilment, `200` streaming after.
- Confidentiality: another client's request id → `404`, not `403`.

That is the whole HTTP surface worth an end-to-end test. The rest of each endpoint's behaviour —
validation, error mapping, projection — is cheaper to test one layer down.

---

## 5 · Event contract tests

For each event in `04 §3`, serialise an instance and assert it matches the documented example
payload — a Jackson-level unit test, one per event. It catches an envelope or field drifting from
the contract.

Consumer-driven contract testing (Pact, Spring Cloud Contract) is the evolution once there is a real
external consumer to pin against. For an MVP with one documented trail consumer it is machinery
without a counterparty.

---

## 6 · Test data

The challenge asks for fixtures sufficient to run the integration tests in a clean environment.

- **Reference rows** — auditors, clients, the `supplier`/`site` catalogue — a versioned SQL seed
  script, applied after Liquibase on the fresh container.
- **Scenario rows** — the specific audits or requests a test needs — created by that test, per class
  (`@Sql` or a builder), never shared mutable state between tests.
- The container is reused; the data is not. Each test class starts from the seed and cleans up, or
  runs in a rolled-back transaction where it can.

---

## 7 · Coverage

JaCoCo, **measured and reported, not chased.** The >90% line target comes from the sibling
challenge, not this one; optimising for it trades real testing time for a number nobody here will
read.

The bar is qualitative and it is met when these are covered: the window arithmetic (every level,
the leap-day rule, the Premium floor), the concurrency net (with the drop-the-index proof), every
`CHECK` and unique constraint, idempotency replay, and both state machines including the transitions
that must *not* be reachable.

---

## 8 · Tooling

| Tool | Where | Note |
|---|---|---|
| **JUnit 5** + **AssertJ** | everywhere | parameterised tests for the per-level window arithmetic |
| **Mockito** | the use-case seam only | stub the repository *ports* so the assignment decision is tested without a database. Never on domain objects — a model that needs heavy mocking is anaemic, and saying so is a point in the model's favour |
| **Testcontainers** | integration + concurrency | Postgres 18, one singleton container for the suite |
| **Awaitility** | the async assertions | poll for the worker / sweep outcome instead of sleeping |

Not used, on purpose: an in-memory database (§3), BDD frameworks (the domain reads clearly without a
Gherkin layer), Pact (§5), load-testing tools (the concurrency test is about correctness, not
throughput).

---

## 9 · What a green suite proves, and what it does not

**Proves:** the domain invariants hold; the database catches a double booking even when the
application logic is wrong; retries do not create a second commitment; a slipped audit does not
fulfil a request early.

**Does not prove:** throughput or latency under load; real broker delivery semantics (the relay and
consumers are tested against a stub); authentication and tenancy (out of scope, A10 / 03 §7).

It also does not prove that every *accepted* request's contractual ceiling is met. The one
identified case where it is not — a tight Premium ceiling against a site's in-flight audit (A7,
`07 §2`) — is accepted knowingly and surfaces as an `AuditRequestUnschedulable` event, not as a
test failure. Preventing it at acceptance time is the deferred evolution, not a defect the suite
should catch.

Stated so the boundary of the evidence is explicit.
