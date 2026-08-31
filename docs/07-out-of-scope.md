# 07 · Out of scope

**Framing: this is an MVP** — a sound initial structure with real functionality, not a finished
product. Every item here is a case the design *surfaced and answered on paper*. Building it is an
evolution, not a correction. This is the single place they live; the other documents point here.

The one-line defence: the problem statement prefers *one small part totally covered* over a complete
solution, so the breadth is declared, not forgotten.

---

## 1 · Rescheduling anything already committed (A5)

Cancelling a *request* is in scope — it is a terminal transition that only frees capacity. Moving an
audit that other clients have planned around is a second complete problem: it drags in fairness
recalculation and compensation of events already emitted.

**Intended policy when built:**

| Trigger | Policy |
|---|---|
| Client cancels | Date released as `AuditSlotReleased`; already-committed audits are **not** moved; the cancelling client re-enters the queue. |
| Auditor unavailable | Reassignment optimised to change the **fewest committed dates**. Affected requests take priority — the delay is the provider's fault. |
| Processing delay | `published_at` recomputed; the audit date is untouched. |

The asymmetry is deliberate: disruption the provider caused is absorbed by the provider; disruption
the client caused returns the client to the queue.

---

## 2 · Pulling an in-flight audit forward (A7, 01 §6)

An audit is scheduled to publish on 01/12/2026. A Premium client requests on 01/09/2026 with a
ceiling of 01/10/2026. The existing audit publishes too late for them, and a second audit would
overlap the site's validity. **Today the request stays `PENDING` and expires as `UNSCHEDULABLE`,
emitting `AuditRequestUnschedulable` — visible, not silent.**

**The fix:** move the in-flight audit earlier instead of creating a second one. Safe in one
direction only — `available_to_client_at = max(published_at, requested_at + min_window)`, so an
earlier publication leaves every attached client's access date unchanged or improves it, and no
ceiling is breached; moving later would push every attached request out at once.

The rule underneath: **while the audit has not occurred its date is negotiable; once it has occurred
its validity is fixed.**

Deferred because it adds a third branch to the assignment worker and a minimum-notice policy toward
the auditor, for one edge case whose failure mode is already an event.

This is the one accepted request whose contractual ceiling the system may fail to meet. It is
recorded, not hidden — see also `06 §9`.

---

## 3 · Auditor eligibility and qualifications (A3, 01 §6)

Any auditor can audit any site. No interface with a single pass-through implementation is introduced
— that is structure without content. When qualifications appear they enter as a **filter before
selection**; the concurrency design is untouched, because a smaller candidate pool does not change
how competition for a date is arbitrated. That the boundary holds here is evidence it sits in the
right place.

---

## 4 · Report structure (01 §6)

The report is `published_at` plus a document reference on `Audit`. It has no lifecycle of its own:
produced by one audit, published once, meaningless detached from it. If findings, versions or
signatures appear it becomes a separate row that still belongs to its audit and is still written in
the same transaction.

---

## 5 · Non-count-based auditor distribution (A2, 01 §6)

"Proportionally" is read as least-loaded by audit count over a rolling window. Selection sits behind
`AuditorSelectionPolicy` — the one interface kept — so weighting by audit duration, specialty or
region is an implementation swap that moves nothing else.

---

## 6 · Variable audit and processing durations (A1)

`audit_duration_days` and `processing_duration_days` are configurable columns with declared
defaults (1 and 7). In this scope they never vary from the default.

- If `audit_duration_days` varies, a slot stops being a point and becomes a range: uniqueness on a
  single date stops being sufficient and selection changes from "is this date free" to "is this
  range free".
- If `processing_duration_days` varies, the request-time window arithmetic and the audit-time
  publication arithmetic stop agreeing; `latest_audit_date` must be recomputed against the audit's
  actual value, or the placement and reuse checks must read it from the audit.

Both changes are localised; nothing else in the design moves.

---

## 7 · Authentication, tenancy, and catalogue lifecycle

- **Auth** is an Auth0 resource-server concern — the application validates a JWT, it does not manage
  credentials. Not the difficulty of the problem posed, so not built (03 §7 states the internal-API
  boundary; client auth is assumed).
- **Multi-tenancy** beyond "a client sees only its own requests" (enforced by `404`, not a tenant
  column) is not modelled.
- **Catalogue lifecycle** — `supplier` and `site` are insert-only (A10). No update, merge,
  deactivation or ownership transfer. A site is never re-parented.

---

## 8 · Client webhooks (04 §6)

A webhook consumer is a projection of the `audit_request` events with `auditId` and every trace of
co-requesters stripped, delivered to a client-registered URL with retry and a signature header. It
is the one consumer that would go through the broker rather than in-process. Not built because it
adds endpoint registration, secret management, retry/back-off and a dead-letter path to reach the
same clients that polling and email already reach.

---

## 9 · Notification channel (04 §5)

The notification component decides *which events* send an email and *to whom*. The channel itself —
templating, the email provider, delivery retry, per-client preferences, other channels — is behind
the `NotificationSender` seam and not implemented. Swapping or extending it moves nothing in the
event or scheduling design.
