# ADR 0002 · Traceability via transactional outbox to a dedicated audit service

**Status:** Accepted · **Related:** A8

## Context

The domain is pharmaceutical supplier compliance. The system that manages audits is itself subject
to audit: it must be possible to reconstruct who requested what, when it was assigned, to whom, and
when the report was published — without gaps, and credibly enough to survive inspection.

## What must not drive this decision

Volume. The system has no traffic forecast, and a decision defended by "the worst case" invites the
question of what that case is. The reasons below hold at any scale, which is what makes them
defensible.

## Requirements

1. **The trail cannot diverge from reality.** A committed business fact with no corresponding entry
   is a compliance failure, not a data quality issue.
2. **The trail must outlive operational retention.** Operational data is archived and pruned on a
   business cadence; regulatory evidence is retained on a regulatory one.
3. **The trail must not be deletable by the service that produces the facts.** Separating the
   writer from the record is what makes the record credible to an inspector.

## Options considered

### Option 1 · Append-only table in the operational database

Satisfies (1) trivially — same transaction, same database. Fails (2) and (3): the record shares
retention and lifecycle with operational data, and the service that writes the facts can also erase
the evidence of them.

**Rejected as the final destination.** Useful only as the handoff point, which is what the outbox
is.

### Option 2 · Audit service fed by broker messages published after commit

Satisfies (2) and (3). Fails (1): if the publish fails after the transaction commits, a business
fact exists with no record of it. This introduces eventual consistency into the one thing that must
not be eventually consistent.

**Rejected.** The failure mode is exactly the one the trail exists to prevent.

### Option 3 · Change data capture from the WAL, or database triggers

Captures every change with no application code and cannot be bypassed. But it captures **what
changed, not why or by whom** — the WAL has no notion of actor, command or business intent. A trail
that cannot answer "who decided this" is not an audit trail.

**Rejected as the primary record.** Reasonable as defence in depth.

### Option 4 · Transactional outbox + relay + dedicated audit service *(chosen)*

## Decision

```
Business transaction
  ├─ state change            ┐
  └─ insert outbox_event     ┘ same transaction — atomic

Relay (separate process, the single reader of the outbox)
  └─ reads unpublished rows → publishes to the broker → marks published

Broker fans out to the subscribers:
  ├─ Audit service   — own database, own retention, own access control
  ├─ Notification consumer  (04 §5) — co-deployed
  └─ Client webhooks (future)
```

The outbox is the reliable event log; the audit service is its first subscriber, not its
definition. `from`/`to` state travels in `payload`; `actor` is a column so every subscriber reads
*who decided this* the same way.

```sql
CREATE TABLE outbox_event (
    id              uuid        PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      text        NOT NULL,
    actor           text        NOT NULL,
    payload         jsonb       NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);
```

| Requirement | Mechanism |
|---|---|
| Cannot diverge from reality | Outbox row committed in the same transaction as the fact |
| Outlives operational retention | Audit service owns its own database and retention policy |
| Not deletable by the producer | The producing service has no write access to the audit store |

The application role holds no `UPDATE` or `DELETE` grant on `outbox_event` beyond setting
`published_at`. Immutability enforced by permissions, not convention.

## Consequences

**Delivery is at-least-once; the consumer must be idempotent.** Deduplication by event id. This is
a property of the pattern, not an oversight — exactly-once delivery does not exist, and claiming it
would be the wrong answer to give.

**The broker is a transport concern, never a durability one.** If the broker is down, outbox rows
accumulate and the relay resumes. The business transaction is unaffected, because it never depended
on the broker being reachable.

**Ordering is per aggregate, not global.** Partition by `aggregate_id` so a single audit's history
is ordered. Global ordering across aggregates is neither achievable nor needed for reconstruction.

**Local retention is short.** `outbox_event` is a handoff buffer, not an archive. Rows are pruned
once published and acknowledged, so growth in the operational database is bounded by relay lag
rather than by system lifetime — which removes the growth concern without a volume estimate.

**Cost accepted.** One extra insert per business transaction, and a relay process to operate. What
it buys is the only guarantee that cannot be reconstructed after the fact: that no committed
business event is missing from the record.
