# CLAUDE.md

Audit-scheduling MVP for the Qualifyze backend challenge. The repo holds a **frozen design**
(`docs/00`–`docs/07`, `docs/adr/`, `docs/diagrams/`) plus a **thin implementation slice** around
assignment concurrency.

## Hard rules

Do not break these without the user's explicit approval **in the same message**.

1. **Never commit or merge on `main`.** Every task = a branch off `main` + a PR. The user reviews
   and squash-merges on GitHub. You never merge.
2. **Never** `git push --force`, `git push` to `main`, `git reset --hard`, `git rebase`,
   `git clean -f`, `git branch -D`, `git checkout/restore .`, or `git add -A` / `git add .`.
   A `PreToolUse` hook enforces this (`.claude/hooks/block-dangerous-git.js`).
3. **Never edit `docs/00-*`…`docs/07-*`, the ADRs, or the diagrams** during an implementation task.
   If the code shows the design is wrong, **stop and say so** — a design change is its own
   conversation, not a side effect.
4. **Never add a dependency, change the build config, or change a public API / DB shape** beyond
   what the current ticket specifies without asking first.
5. **No scope expansion.** If a ticket hides complexity, stop, report it, wait. No "while I'm here".
6. **TDD always.** A failing test first, then the minimum code to pass, then refactor.
7. **Simplest thing that passes.** MVP. No speculative abstraction. An interface with a single
   implementation → stop and ask — **except `AuditorSelectionPolicy`**, kept on purpose (`07 §5`):
   "proportionally" is one reading among several, and this is the one seam where that answer
   changes without moving the concurrency design. Implement it as an interface; don't re-litigate
   whether it should be one.
8. **Every line defensible in one sentence**, or it does not go in.
9. **Do not chase coverage.** JaCoCo is measured, not a target (`docs/06`).
10. **Add files by explicit path.** These gitignored personal notes must never be staged:
    `GUION_DEFENSA.md`, `docs/99-defense-notes.md`, `QUALIFYZE_CONTEXT.md`,
    `PLAN_2_DIAS_QUALIFYZE.md`.
11. **The design vocabulary is binding.** Request states are exactly `PENDING`, `SCHEDULED`,
    `FULFILLED`, `UNSCHEDULABLE`, `CANCELLED`. Audit states are exactly `SCHEDULED`, `IN_PROGRESS`,
    `PUBLISHED`, `DISCARDED`. Never `ATTACHED`, never `CANCELLED` on an audit. Table and column
    names come from `docs/02` verbatim. If a name feels wrong, stop and say so — don't rename
    quietly.
12. **The Liquibase changelog is a transcription of `docs/02`, not an interpretation.** Every
    constraint, index and `CHECK`, with the names given there — both business unique indexes, the
    idempotency index, all five `CHECK`s, all eight indexes, the `updated_at` trigger. No column
    that isn't in `docs/02`. If the code needs one, that's a design conversation (rule 3).

## Stack (locked)

Java 25 · Spring Boot 4 · Gradle · Liquibase · PostgreSQL 18.

**Liquibase: don't pin a version.** Let Spring Boot's dependency-management BOM resolve it.
(Verified: Spring Boot 4.1.1's BOM manages `liquibase-core:5.0.3` — "5" is real, not a typo carried
over from the sibling challenge — but the point stands regardless of the number: pinning it
ourselves is how half an hour gets lost arguing with Gradle instead of building.)

**Persistence: `JdbcClient`, no JPA/Hibernate.** Domain objects mapped to and from rows by hand.
Not a style preference — the worker's placement loop uses a unique-constraint violation as control
flow, in the same transaction, potentially several times per request (`05 §4`,
`diagrams/worker-decision.mermaid`). A JPA flush failure leaves the `EntityManager` rollback-only;
`JdbcClient` surfaces it as a catchable `DuplicateKeyException` and the transaction stays usable.
Full reasoning: [ADR 0004](docs/adr/0004-no-jpa-jdbc-client.md).

Tests: JUnit 5 + AssertJ · Mockito (use-case seam only, never on domain objects) · Testcontainers
(PostgreSQL 18) · Awaitility. No in-memory database — `docs/06` explains why.

**The lock has one valve: the scaffolding timebox — 90 minutes, hard, starting when
`chore/2-scaffolding` branches.** The retreat order is decided now, not at minute 91, so there is
no decision left to make under time pressure:

1. First to fall: **Liquibase** → plain SQL migration scripts. Cheapest to explain, least central
   to anything defended.
2. Only if that alone doesn't clear the block: reconsider the **Spring Boot version** — a version
   already known.

Either way: **stop and say so before switching** — a reported retreat, not a silent one. Outside
scaffolding, the lock has no valve — rule 4 applies as written.

## Implementation scope — the slice

Rule 5 ("no scope expansion") needs something to measure against. This is it: only what's listed
here is built in code. Everything else in the design stays a document.

**In the slice:**
1. Domain: `SubscriptionLevel`, `DeliveryWindow`, `AuditRequest`, `Audit` — invariants and both
   state machines (`docs/01`).
2. `POST /v1/audit-requests` — create, idempotency (`docs/03 §3`).
3. The assignment worker — claim with `SKIP LOCKED`, the reuse check as two reads (`docs/02 §6`),
   placement, the two unique-index races (`docs/05`).
4. An outbox row written in the same transaction as each state change (`docs/00 A8`). The
   relay, broker, trail service and notification consumer are stubs or not built.
5. The concurrency test — N threads, zero double bookings, and the drop-the-index proof
   (`docs/05 §9`, `docs/06 §4`).

**Out of the slice** (designed and documented, not coded here): every other endpoint
(`POST /v1/sites`, the `GET`s, report streaming, publication), the daily fulfilment sweep, the
real notification consumer, the real broker/relay, webhooks.

**Tickets, in order:**

| # | Branch | Delivers |
|---|---|---|
| 1 | `chore/1-dev-guardrails` | done |
| 2 | `chore/2-scaffolding` | Spring Boot + Liquibase changelog + Testcontainers wiring — 90 min timebox (see Stack) |
| 3 | `feat/3-domain` | `SubscriptionLevel`, `DeliveryWindow`, `AuditRequest`, `Audit` |
| 4 | `feat/4-create-request` | `POST /v1/audit-requests` + idempotency |
| 5 | `feat/5-assignment-worker` | claim, reuse check, placement, outbox write |
| 6 | `test/6-concurrency` | the N-threads test + drop-the-index proof |

**Ticket 2's exit test proves behaviour, not presence.** Don't query the catalog for whether a
constraint exists — insert two audits with the same auditor and date and assert the database
rejects the second. Querying the catalog proves the line was written; inserting proves the line
does something. The red→green cycle here isn't really TDD (there's no unit of behaviour to drive
out) — its value is proving Testcontainers, Liquibase and Spring Boot actually wire together, which
is the real risk in this ticket.

## Workflow per task

1. `git switch main && git pull`
2. `git switch -c <type>/<n>-<slug>`  — type ∈ {feat, fix, test, refactor, chore}
3. Implement TDD, following `docs/06-testing-strategy.md`
4. Full test suite green
5. Review the diff (`/code-review` or a careful self-review)
6. Commit (format below)
7. `git push -u origin <branch>` and hand the user the PR compare link
8. **Stop.** The user reviews and squash-merges on GitHub.
9. After merge: `git switch main && git pull`, clear context, next task.

## Commit messages

`<type> : <lowercase summary>` — type ∈ {feat, fix, test, refactor, docs, chore}.

## Read before implementing

`docs/01` domain model · `docs/02` data model · `docs/05` concurrency · `docs/06` testing strategy.
The one-paragraph version of the whole system is `docs/05 §10`.
