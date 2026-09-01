# ADR 0004 · No JPA: `JdbcClient` for the assignment worker

**Status:** Accepted · **Related:** ADR 0001, ADR 0003, `05`, `06`

## Context

The worker's placement loop (ADR 0001, `05 §4`, `diagrams/worker-decision.mermaid`) uses a
constraint violation as **control flow**, not as an exceptional failure:

```
INSERT audit (auditor, date)
  → UNIQUE (auditor_id, audit_date) violated → next candidate → retry
  → otherwise                                → attached, done
```

This happens **inside the same transaction**, potentially several times per claimed request. The
persistence technology has to support that shape without fighting it.

## What JPA/Hibernate would cost here

A `23505 unique_violation` surfacing through Hibernate is a **flush failure**. Past that point the
`EntityManager` is in an invalid state: the transaction is marked rollback-only, and no further
work can happen on it. "Try the next candidate" would then require either:

- a **new transaction per attempt** — turning one logical placement into N transactions, each
  paying commit overhead, for a loop that is expected to run once in the common case; or
- **checking availability before inserting**, to never reach the constraint — which is exactly the
  ergonomics-only check `01 §3` already describes as a *convenience* layered on top of the
  constraint, not a replacement for it. Making it load-bearing to avoid a flush failure would
  quietly move the guarantee back into application code, which `05` is explicit about not doing:
  the guarantee lives in the engine because code can't see across concurrent transactions.

Neither option is acceptable for a loop the design deliberately built around cheap, in-transaction
retries.

## Decision

**No JPA. `JdbcClient` (Spring's statement-oriented JDBC API), with the domain objects mapped to
and from rows by hand.**

With `JdbcClient`, a unique-index violation surfaces as a plain `DuplicateKeyException` from a
single `INSERT`. The transaction is untouched — nothing was flushed, nothing is pending. The worker
catches it and tries the next candidate **in the same transaction**, exactly as
`worker-decision.mermaid` draws it.

## Two supporting arguments

- **`FOR UPDATE SKIP LOCKED` (ADR 0001) is idiomatic SQL and contorted through JPA.** It has no
  first-class mapping in JPQL/Criteria; reaching for it usually means a native query anyway. With
  `JdbcClient` it's just the SQL the design already specifies.
- **ADR 0003's "no `version` column" reads as a decision with `JdbcClient`, and as an oversight
  with JPA.** JPA's optimistic-locking convention primes a reviewer to look for `@Version`; its
  absence invites a question the design has already answered, for a different reason, in a
  different document. `JdbcClient` has no such convention to depart from — there's nothing to
  explain.

## Cost accepted

Hand-written mapping for two written tables and four read tables. Small, and it is the same cost a
rich domain model already pays to keep persistence a detail: `01` chose behaviour-bearing objects
over an ORM's managed entities before this ADR existed. `JdbcClient` is the client that fits the
choice already made, not a new one.

## Not "rich domain needs no JPA"

Framing this as "a rich domain model doesn't need JPA annotations" is true but not the reason: a
mapper class would solve that just as well while still routing inserts through an
`EntityManager`. The reason is narrower and specific to this design — the worker's retry loop
depends on a failed insert leaving the transaction usable, which is a property JDBC has and JPA
does not.
