# 04 · Event contracts · asynchronous

The synchronous contract is [`03`](03-api-contracts.md). This is what leaves the system
asynchronously: the audit trail (A8), the notification emails, and — later — client webhooks.

**One mechanism.** Every event is written to `outbox_event` in the same transaction as the state
change that produced it (A8). A relay publishes unpublished rows to the broker. Every consumer is
**idempotent by `eventId`**; delivery is **at-least-once**. There is no "exactly once" — a consumer
that must not act twice keeps its own dedup key (the notification component does, §4).

---

## 1 · The envelope

Every event carries the same outer shape; `payload` varies by type.

```json
{
  "eventId": "018f9c2a-...-7b31",
  "eventType": "AuditRequestScheduled",
  "occurredAt": "2026-08-31T09:14:07Z",
  "aggregateType": "audit_request",
  "aggregateId": "018f5b71-...-4c02",
  "payload": { }
}
```

`eventId` is the deduplication key for every consumer. `occurredAt` is the transaction time, not the
relay time. `payload` is `jsonb` in the outbox and JSON on the wire.

---

## 2 · Events this system produces

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
| `AuditSlotReleased` | `audit` | discard frees a committed date | `auditorId, date` | the assignment worker |

**`auditId` appears in `AuditRequestScheduled`.** Its consumers — the trail and the notification
component — are trusted internal services. A future *webhook* projection (§5) strips `auditId` and
anything about co-requesters before anything reaches a client (A7).

**`AuditPublished` does not notify clients directly.** It triggers the in-process fan-out that moves
attached requests to `FULFILLED`; each of those emits its own `AuditRequestFulfilled`, which is the
client-facing event. A raw `AuditPublished` would notify clients whose minimum window has not
elapsed.

---

## 3 · Events this system consumes · integration contracts

Produced by contexts outside this scope; this system subscribes to them. Documented because the
shape it expects to receive is as much a contract as the shape it sends.

| Event | Payload expected | Effect here |
|---|---|---|
| `AuditorOnboarded` | `auditorId, activeFrom` | Worker retries pending requests whose window is still open |
| `AuditorAvailabilityOpened` | `auditorId, date` | Worker retries pending requests whose window contains `date` |
| `SubscriptionChanged` | `clientId, newLevel, effectiveFrom` | Informational only — levels are frozen on the request (A9), so scheduling ignores it. Recorded for the trail. |

Each is consumed idempotently by `eventId`; a replay is a no-op because the worker's query returns
nothing new.

---

## 4 · The notification component

A **separate consumer of the broker**, not a new mechanism — the same argument as the audit trail
(A8): the events already exist, delivering them as email is one more subscriber.

**What it sends.** One email per event, to `client.contact_email`:

| Event | Email |
|---|---|
| `AuditRequestCreated` | "We received your request for {site}." |
| `AuditRequestScheduled` | "Your audit is scheduled. Report expected around {expectedReportDate}." — the brief's *audit assignment* notification |
| `AuditRequestFulfilled` | "Your report is ready." — the brief's *report delivery* notification |
| `AuditRequestUnschedulable` | "We could not schedule your audit within the committed window." |

The channel — templating, the email provider — is out of scope, behind a `NotificationSender` seam.

**Concurrency.** This is the concurrency question the brief attaches to notifications: many events
arriving fast, several consumer instances, and the requirement that no client email is lost and
near-none is duplicated.

```sql
-- claim, then send
INSERT INTO notification_dispatch (event_id, channel, recipient, status)
VALUES ($1, 'email', $2, 'CLAIMED')
ON CONFLICT (event_id) DO NOTHING;
-- if a row was inserted: send the email, then UPDATE status = 'SENT', sent_at = now()
-- if not: another instance owns this event — skip
```

`event_id` is the primary key of `notification_dispatch` (§02), so the claim is atomic across
instances. Exactly one instance sends. A crash between the send and the `SENT` update leaves the row
`CLAIMED`; a sweep re-sends it — **at-least-once**, so a rare duplicate email is possible and a lost
one is not. Stating it plainly: there is no exactly-once here, and claiming otherwise would be the
wrong answer.

---

## 5 · Client webhooks · not built

A webhook consumer is a **projection of the `audit_request` events** in §2 — `Created`, `Scheduled`,
`Fulfilled`, `Unschedulable` — with `auditId` and every trace of co-requesters removed, delivered to
a client-registered URL with retry and a signature header.

Not built because it adds endpoint registration, secret management, retry/back-off and a dead-letter
path to reach the same clients that polling and email already reach. The events are in the outbox;
when it is built it is a subscriber, not a change to this system.
