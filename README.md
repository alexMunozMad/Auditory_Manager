# Audit scheduling · Qualifyze backend challenge

Clients on a subscription request compliance audits of their suppliers' sites. The system assigns an
auditor and a date under three simultaneous constraints — **one auditor per site per day**,
**proportional distribution across auditors**, and **a delivery window set by the subscription
level** — and publishes the resulting report.

This repository is the **design** for one part of that problem, covered in depth (`docs/`), plus a
slice of implementation as evidence (`service/` — Java 25, Spring Boot 4, `./mvnw test`, needs
Docker for Testcontainers).

## Scope

**Covered** — the life of an audit request from acceptance to auditor assignment to report
publication: the domain model, the relational model, the HTTP and event contracts, the concurrency
design, and the testing strategy. The weight is on **assignment concurrency**, because that is where
the problem is actually hard.

**Excluded** (identified, answered on paper, not built — see [`docs/07`](docs/07-out-of-scope.md)):
rescheduling a committed audit, auditor qualifications, report versioning, client webhooks, auth and
tenancy.

## The three decisions this rests on

1. **An audit is a fact about a site, not about a client.** Several clients attach to the same audit
   while it is valid, so duplicate demand becomes reused supply and auditor load drops. The
   highest-leverage decision in the design, and it is not a technical one.
2. **Assignment is asynchronous and single-writer.** Moving it off the request path turns a
   concurrency problem into a scheduling one: with one writer, proportional distribution is correct
   by construction — the contention on the workload figure disappears.
3. **Business rules are enforced in the database.** The real races — the auditor calendar, the site
   calendar — are arbitrated by unique indexes the application cannot bypass. The domain gives
   useful errors; the database gives the guarantee.

## Documents

| # | Document | |
|---|---|---|
| 00 | [`assumptions`](docs/00-assumptions.md) | A1–A10 — every ambiguity, the assumption taken, its cost if wrong |
| 01 | [`domain-model`](docs/01-domain-model.md) | aggregates, invariants, the two state machines |
| 02 | [`data-model`](docs/02-data-model.md) | DDL, indexes and constraints, each against a query |
| 03 | [`api-contracts`](docs/03-api-contracts.md) | the HTTP contract — client and internal |
| 04 | [`event-contracts`](docs/04-event-contracts.md) | the outbox, the events, the notification component |
| 05 | [`concurrency`](docs/05-concurrency.md) | the centre of the design, as one narrative |
| 06 | [`testing-strategy`](docs/06-testing-strategy.md) | the pyramid, and the test that proves it |
| 07 | [`out-of-scope`](docs/07-out-of-scope.md) | what was deliberately not built, and why |
| — | [`adr/`](docs/adr/) | 0001 async assignment · 0002 audit trail · 0003 no optimistic locking · 0004 no JPA |
| — | [`diagrams/`](docs/diagrams/) | system context ([legend](docs/diagrams/context-legend.md)) · ER model · request lifecycle and events · worker decision · audit-request states · audit states |
