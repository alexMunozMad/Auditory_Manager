# 04 · Event contracts · asynchronous

The synchronous contract is [`03`](03-api-contracts.md). This is what leaves the system
asynchronously: the audit trail (A8), the notification emails, and — later — client webhooks.

---

## 1 · One outbox, one relay, a broker for fan-out

Every event is written to `outbox_event` in the same transaction as the state change that produced
it (A8). **The outbox is the reliable event log — not "the trail".** The audit-trail service is its
first consumer, not its definition.

A single **relay** reads the outbox (its cursor is `published_at`) and publishes every row to the
**broker**, which fans out to the subscribers:

| Subscriber | Deployment | Takes |
|---|---|---|
| **Audit-trail service** | external — its own database, retention and access control (ADR 0002) | every event |
| **Notification consumer** | co-deployed in this application | the `AuditRequest*` events (§5) |
| **Client webhooks** | future | a client-facing projection (§6) |

Two things do **not** go through the broker:

- The **fulfilment fan-out** on `AuditPublished` runs inside the publication transaction: it updates
  the attached requests and emits their `AuditRequestFulfilled` rows. That is domain logic, not a
  subscriber.
- **Capacity signals** to the assignment worker use `LISTEN/NOTIFY` (ADR 0001). `AuditSlotReleased`
  is produced and consumed inside this system, so it `NOTIFY`s the worker directly rather than
  taking a broker round-trip — while still being written to the outbox for the trail.

Every subscriber is **idempotent by `eventId`**; delivery is **at-least-once**. There is no
"exactly once" — a subscriber that must not act twice keeps its own dedup key (the notification
consumer does, §5).

---

## 2 · The envelope

```json
{
  "eventId": "018f9c2a-...-7b31",
  "eventType": "AuditRequestScheduled",
  "occurredAt": "2026-08-31T09:14:07Z",
  "aggregateType": "audit_request",
  "aggregateId": "018f5b71-...-4c02",
  "actor": "worker:assignment",
  "payload": { }
}
```

`eventId` is the deduplication key for every subscriber. `occurredAt` is the transaction time, not
the relay time. `actor` — the client, an operator, or a named system process — is what makes the
trail able to answer *who decided this* (ADR 0002); it is a column on `outbox_event`, not buried in
`payload`. Any `from`/`to` state lives in `payload`.

---

## 3 · Events this system produces

| Event | Aggregate | Trigger | Payload | Consumers |
|---|---|---|---|---|
| `AuditRequestCreated` | `audit_request` | `POST /v1/audit-requests` accepted | `requestId, clientId, siteId, subscriptionLevel, commitment{reportNoEarlierThan, reportNoLaterThan}` | trail, notifications |
| `AuditRequestScheduled` | `audit_request` | attached to an audit — newly scheduled or reused | `requestId, clientId, auditId, expectedReportDate, commitment` | trail, notifications |
| `AuditRequestFulfilled` | `audit_request` | `SCHEDULED → FULFILLED` (publication fan-out or daily sweep) | `requestId, clientId, availableSince` | trail, notifications |
| `AuditRequestUnschedulable` | `audit_request` | daily sweep past `latest_audit_date` with no placement | `requestId, clientId, reason, latestAuditDate` | trail, notifications, ops |
| `AuditRequestCancelled` | `audit_request` | operations cancellation | `requestId, clientId, reason` | trail |
| `AuditScheduled` | `audit` | a new `audit` row is created | `auditId, siteId, auditorId, auditDate` | trail |
| `AuditPublished` | `audit` | `POST /internal/v1/audits/{id}/publication` | `auditId, siteId, publishedAt, validUntil` | trail; fulfilment fan-out (in-process) |
| `AuditDiscarded` | `audit` | last attached request cancelled | `auditId, siteId, auditDate` | trail |
| `AuditSlotReleased` | `audit` | a discard frees a committed date | `auditorId, date` | trail (via broker); **the assignment worker (via `NOTIFY`, §1)** |

**`AuditSlotReleased` does not round-trip the broker to reach the worker.** It is produced and
consumed inside this system: the discard `NOTIFY`s the worker in the same transaction (ADR 0001). It
is still written to the outbox — a released auditor-day is an auditable fact — so the trail gets it
via the relay like everything else. Two paths, one event. This is unlike the two external capacity
events (§4), which have no shortcut, and unlike `AuditRequestCancelled`, which is an in-transaction
domain event coordinating two aggregates rather than a signal to the assignment loop.

**`auditId` appears in `AuditRequestScheduled`.** Its consumers — the trail and the notification
component — are trusted internal code. A future *webhook* projection (§5) strips `auditId` and
anything about co-requesters before it reaches a client (A7).

**`AuditPublished` does not notify clients directly.** It triggers the in-process fan-out that moves
attached requests to `FULFILLED`; each emits its own `AuditRequestFulfilled`, which is the
client-facing event. A raw `AuditPublished` would notify clients whose minimum window has not
elapsed.

---

## 4 · Events this system consumes · integration contracts

Produced by contexts outside this scope; a **broker consumer** in this application subscribes and
calls the relevant internal handler. Documented because the shape this system expects to receive is
as much a contract as the shape it sends.

| Event | Payload expected | Effect here |
|---|---|---|
| `AuditorOnboarded` | `auditorId, activeFrom` | Worker retries pending requests whose window is still open |
| `AuditorAvailabilityOpened` | `auditorId, date` | Worker retries pending requests whose window contains `date` |
| `SubscriptionChanged` | `clientId, newLevel, effectiveFrom` | Informational only — levels are frozen on the request (A9, A6), so scheduling ignores it; recorded for the trail |

Each is consumed idempotently by `eventId`; a replay is a no-op because the worker's query returns
nothing new.

---

## 5 · The notification consumer

A **broker subscriber co-deployed in this application** — the same codebase and deployment as the
API and the worker, but on the receiving end of the broker like any other subscriber. It does not
touch the outbox; the relay is the only reader of that. It sends one email per event and records the
send in `notification_dispatch` (§02).

Its table lives in this schema legitimately: it is this deployment's send-ledger, and the ADR 0002
rule that a *trail* must not be deletable by its producer does not apply to a "which emails did we
send" log.

**What it sends**, to `client.contact_email`:

| Event | Email |
|---|---|
| `AuditRequestCreated` | "We received your request for {site}." |
| `AuditRequestScheduled` | "Your audit is scheduled. Report expected around {expectedReportDate}." — the brief's *audit assignment* notification |
| `AuditRequestFulfilled` | "Your report is ready." — the brief's *report delivery* notification |
| `AuditRequestUnschedulable` | "We could not schedule your audit within the committed window." |

The channel — templating, the email provider, retry, per-client preferences — is out of scope,
behind a `NotificationSender` seam (07 §9).

**Concurrency.** This is the concurrency question the brief attaches to notifications: many events
arriving fast, several instances of the consumer, at-least-once redelivery from the broker, and the
requirement that no client email is lost and near-none is duplicated.

```sql
-- claim, then send
INSERT INTO notification_dispatch (event_id, channel, recipient, status)
VALUES ($1, 'email', $2, 'CLAIMED')
ON CONFLICT (event_id) DO NOTHING;
-- a row was inserted  → send the email, then UPDATE status = 'SENT', sent_at = now()
-- no row was inserted  → this event was already handled, skip
```

`event_id` is the primary key of `notification_dispatch`, so the claim is atomic across instances
and across redeliveries — exactly one send happens. A crash between the send and the `SENT` update
leaves the row `CLAIMED`; a sweep re-sends it. **At-least-once**: a rare duplicate email is possible,
a lost one is not. There is no exactly-once here, and claiming otherwise would be the wrong answer.

---

## 6 · Client webhooks · not built

A webhook consumer is a **projection of the `audit_request` events** in §3 — `Created`, `Scheduled`,
`Fulfilled`, `Unschedulable` — with `auditId` and every trace of co-requesters removed, delivered to
a client-registered URL with retry and a signature header.

Not built because it adds endpoint registration, secret management, retry/back-off and a dead-letter
path to reach the same clients that polling and email already reach (07 §8). It is a broker
subscriber like the others; when it is built it is a subscriber, not a change to this system.
