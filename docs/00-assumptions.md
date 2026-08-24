# 00 · Assumptions

Guiding rule: decide what the statement supports. Anything that would require inventing data is
modelled as an **extension point**, not implemented.

---

## A1 · Fixed durations; publication is derived from the audit date

**Decision.** Both durations are configurable columns on the audit, with defaults:

| Field | Default | Meaning |
|---|---|---|
| `audit_duration_days` | 1 | Days the auditor is occupied on site |
| `processing_duration_days` | 7 | Days between the audit and report publication |

```
published_at = audit_date + processing_duration_days
```

**Consequence — the subscription window becomes a search interval on the audit date.**

```
 (requested_at + min_window − processing) < audit_date < (requested_at + max_wait − processing)
```

| Level | Earliest audit date | Latest audit date | Scheduling horizon |
|---|---|---|---|
| Essentials | requested + 21d | requested + 4 months − 7d | 4 months |
| Advanced | requested + 14d | requested + 3 months − 7d | 3 months |
| Premium | tomorrow | requested + 1 month − 7d | 1 month |

```
 month = 30 days
```

The assignment algorithm reduces to: **find the earliest free date inside the interval, held by the
least loaded eligible auditor.**

**Availability is computed, not materialised.** A date is free for an auditor when no audit row
exists for that pair. No slot table is generated; the horizon is implicit and bounded by the widest
`max_wait` (4 months).

### The uniqueness constraint must stay narrow

The business rule is *one auditor audits at most one site per day*. That is exactly:

```sql
UNIQUE (auditor_id, audit_date)
```

Adding `site_id` to the key would **weaken** it: it would permit the same auditor to hold two
audits on the same day for different sites, which is the case the rule forbids. An auditor running
three consecutive audits for different sites is already satisfied by this key — three distinct
dates, three rows, no conflict.

**If `audit_duration_days` becomes variable**, a slot stops being a point and becomes a range. An
audit starting on D with duration 3 occupies D, D+1 and D+2, and a second row starting on D+1 does
*not* violate uniqueness on the start date while genuinely overlapping. Uniqueness on a single date
stops being sufficient, and the selection logic changes from "is this date free" to "is this range
free". Nothing else in the design moves.

---

## A2 · Assignment is asynchronous, driven by a durable request table

**Decision.** `POST /audit-requests` persists the request as `PENDING` and returns immediately. A
background worker performs assignment. The request row **is** the queue.

Full rationale and rejected alternatives: [ADR 0001](adr/0001-asynchronous-assignment.md).

**Why asynchronous.** Not for latency. Moving assignment out of the request path lets the system
control the degree of parallelism. With a single writer, assignments serialise and proportional
distribution becomes trivially correct — contention on the workload counter disappears by
construction. A concurrency problem becomes a scheduling problem.

**Why the database first.** The API has already returned `201`. If the process dies before an
in-memory event is handled, the request is lost with no record it existed. Durability first,
processing second.

**Fairness policy.** Least loaded eligible auditor over a rolling window, ties broken by auditor id
for reproducibility. Behind an interface (`AuditorSelectionPolicy`), so weighting by duration,
specialty or region is an implementation swap.

---

## A3 · Any auditor can audit any site

**Decision.** No qualification, certification, language or country constraints. The only eligibility
rule is availability.

**Why it holds.** The statement describes an auditor with no such attributes. Introducing them means
solving a problem nobody posed.

**Consequence.** Eligibility is a filter applied before selection whose current implementation
returns every auditor.

**If eligibility rules exist.** A qualification table appears and the filter narrows the candidate
set. **The concurrency mechanism is unaffected** — it shrinks the pool, it does not change how
competition for a date is arbitrated. Worth stating explicitly: it is evidence the boundary between
selection policy and concurrency control sits in the right place.

---

## A4 · A request with no feasible date is retried on capacity change, never rejected

**Decision.** If no assignment fits the interval, the request stays `PENDING`. It is retried when
capacity changes, not on a timer. Only once `latest_audit_date` has passed does it become
`UNSCHEDULABLE`, emitting an event for manual intervention. The API never returns an error for this.

**The deadline is not arbitrary — it is derived.** Each request carries its own:

```
latest_audit_date = requested_at + max_wait − processing_duration_days
```

**Retry is condition-driven, not time-driven.** A request fails to place because its window is
full. The window is fixed, so the passage of time changes nothing: it will still be full in five
minutes. The only events that change the outcome are capacity events —
`AuditorAvailabilityOpened`, `AuditorOnboarded`, `AuditSlotReleased`. Each carries
`(auditor_id, date)`, so the worker queries only the pending requests whose interval contains that
date, ordered by deadline. A daily sweep remains as a safety net and expires requests past their
deadline.

**Why not reject.** The maximum wait is a contractual commitment, not a validity condition. In an
audited system, the record of a commitment that could not be met is precisely the data worth
keeping.

---

## A5 · Request cancellation is modelled; rescheduling is not

**Decision.** Cancelling a *request* is in scope: it is a terminal state, and when the last request
attached to a not-yet-started audit is withdrawn, the audit is cancelled and its date released.
Everything that requires **moving an audit that others depend on** — auditor unavailability,
processing delays, reassignment — stays out.

**Why the line falls there.** Cancelling a request removes a commitment and frees capacity: nothing
downstream has to be recomputed. Rescheduling changes a date other clients have already planned
around, which drags in fairness recalculation and compensation of events already emitted. One is a
terminal transition; the other is a second complete problem.

State transitions originate from specific states rather than a wildcard, so the remaining cases stay
additive.

**Intended policy**, sketched in `07-out-of-scope.md`:

| Trigger | Policy |
|---|---|
| Client cancels | The date is released and offered to the queue as an `AuditSlotReleased` event. Already-committed audits are **not** moved — clients plan around a committed date, and shifting it is worse than leaving a gap. The cancelling client re-enters the queue. |
| Auditor unavailable | Reassignment optimised to **change the fewest committed dates possible**. Affected requests take priority, since the delay is not attributable to the client. |
| Processing delay | Publication recomputed; the audit date is untouched. |

The asymmetry is deliberate: disruption caused by the provider is absorbed by the provider;
disruption caused by the client returns the client to the queue.

---

## A6 · A client holds a single active subscription level, frozen at request time

**Decision.** The subscription belongs to the client, one active level at a time. The delivery
window is resolved from the level in force at the moment of the request, and frozen onto the
request.

**Why freezing matters.** If the client upgrades later, audits already requested keep their original
commitment. This makes the system auditable and avoids recomputing obligations already incurred.

**If the level varies per supplier.** The subscription hangs off the client–supplier relationship.
The change stays local, precisely because the level is already frozen onto the request.

### No temporal subscription table

The level lives on the client as `subscription_level_code` + `subscription_valid_until`. The tier
parameters are an enum in code, not a table:

```
ESSENTIALS   minWindow 28   maxWait 120
ADVANCED     minWindow 21   maxWait  90
PREMIUM      minWindow  0   maxWait  30
```

**A subscription level is not data the system stores; it is a rule it applies.** As a table, a
delivery commitment could be changed with an `UPDATE` in production and no review. As an enum, it
takes a commit, a review and a deployment — the friction such a change deserves in a regulated
domain. When levels are negotiated per client they become data, and the enum becomes the defaults.

A table of dated subscription periods would be a **third copy of history that already exists twice**:
the level under which each request was accepted is frozen on the request itself, and every change of
level is recorded as a `SubscriptionChanged` event in the audit trail (A8). Three records of one
fact is three chances for them to disagree.

**Derived dates are frozen, not recomputed.** `latest_audit_date` and `available_to_client_at` are
stored as resolved dates on the request, never recalculated at query time from the tier parameters.
Otherwise adjusting a parameter would retroactively change commitments made months earlier. With the
dates frozen, the parameters supply defaults for new requests and the past becomes immutable by
construction.

**Known limitation.** `subscription_valid_until` is mutable: renewing overwrites the previous value.
Acceptable, because acceptance of a request is itself the evidence that validation passed at that
moment, and the level under which it was accepted is preserved on the request row.

---

## A7 · An audit is a fact about a site, valid for twelve months, shared across clients

**Decision.** An audit does not belong to the client who requested it. It is a fact about a **site**,
valid for one year from publication. Requests from **different clients** attach to the same audit
while it remains valid.

```
valid_until = published_at + 1 year
```

An audit published on 03/08/2026 is reusable until 03/08/2027. A request whose access date falls on
04/08/2027 requires a new audit.

This is the highest-leverage rule in the design: it turns duplicate demand into reused supply and
directly reduces auditor workload.

```
audit_request  N ──────── 1  audit
   (per client)              (per site, 12-month validity)
```

**Reuse eligibility.** A pending request attaches to an existing audit — rather than triggering a
new assignment — when **both** hold:

```
available_to_client_at ≤ audit.valid_until          -- the audit is still valid for this client
available_to_client_at ≤ requested_at + max_wait    -- the contractual ceiling is met
```

Audits in `SCHEDULED`, `IN_PROGRESS` and `PUBLISHED` are all candidates. For the first two,
`published_at` is the projection `audit_date + processing_duration_days`.

**Delivery is gated per request, not per audit.** An already-published report cannot be handed to a
new Essentials client immediately: that would breach their "no earlier than 4 weeks". The audit's
publication date and the client's access date are different things:

```
available_to_client_at = max( audit.published_at , request.requested_at + min_window )
```

### The effective reuse window differs by subscription level

Because the minimum window pushes the access date forward, the first condition reduces to
`requested_at + min_window ≤ published_at + 1 year`. Higher tiers can therefore reuse an audit for
longer. For an audit published on 03/08/2026:

| Level | min window | Last day a request can still reuse it |
|---|---|---|
| Premium | 0 | 03/08/2027 |
| Advanced | 21 days | 13/07/2027 |
| Essentials | 28 days | 06/07/2027 |

A non-obvious consequence worth stating explicitly: subscription level does not only govern how
fast a client is served, it governs **how much of the existing audit inventory that client can
draw on**.

*Leap years:* validity is computed with calendar-year arithmetic, so 29/02 maps to 28/02 of the
following year. The shorter interpretation is the conservative one in a regulated context.

### Validity periods for a site never overlap

At most one audit is valid for a site at any moment. A new audit for a site can only become
available the day after the previous one expires. This adds a lower bound to the scheduling window
derived from the site's own history:

```
earliest_publication = max( requested_at + min_window , previous_audit.valid_until + 1 day )
audit_date           = earliest_publication − processing_duration_days
```

One constraint enforces this, and it is an ordinary partial unique index:

```sql
CREATE UNIQUE INDEX ON audit (site_id)
    WHERE status IN ('SCHEDULED', 'IN_PROGRESS');
```

**Why no range constraint over the validity periods.** Two published validity periods for a site can
only overlap by one of two routes: two audits were in flight at the same time, or the floor above
was computed wrongly. The index rules out the first — the actual race, two workers creating an audit
for the same site at once. The second is a single line of arithmetic in a single place, and a line
of arithmetic is covered by a test, not by a constraint.

A range-exclusion constraint would therefore be defending against a bug rather than against
concurrency, which does not justify its cost.

**Cost accepted.** If assignment is ever parallelised per site, the floor calculation loses its
backstop. The partial index still prevents concurrent in-flight audits, so the exposure is limited
to arithmetic — but it is recorded here rather than discovered later.

### Known limitation · a tight ceiling against an in-flight audit

The non-overlap rule collides with the contractual ceiling in one case. An audit is scheduled to
publish on 01/12/2026. A Premium client requests on 01/09/2026 with a ceiling of 01/10/2026. The
existing audit publishes too late to serve them, and a second audit would overlap.

**In this scope the request stays `PENDING` and expires as `UNSCHEDULABLE`.** The failure is visible
and emits an event; it is not silent.

**The fix, when built:** move the in-flight audit earlier instead of creating a second one. Safe in
one direction only — an earlier publication either leaves each attached client's access date
unchanged or improves it, so no ceiling can be breached, whereas moving later pushes every attached
request out at once. The rule underneath: *while the audit has not occurred its date is negotiable;
once it has occurred its validity is fixed.*

Deferred because it adds a third branch to the assignment worker and a minimum-notice policy toward
the auditor, in exchange for an edge case. See `01-domain-model.md` §6.

### Audits are demand-driven, never speculative

If an audit expires and no request is pending, no audit is created. The site simply has no valid
audit until demand appears, and the date of the next one is then determined by the subscription
level of whoever requests it. Coverage gaps are an expected state, not an anomaly to be prevented
by scheduling ahead.

**Competing requests do not need a tie-break rule.** When several pending requests target the same
site, the audit is scheduled to satisfy the **tightest deadline** among them; the per-request
gate above handles each client's earliest date. Meeting the lowest ceiling automatically meets the
higher ones. One audit, several commitments honoured, no priority ordering required.

**Confidentiality.** Requesters are not exposed to each other. A client sees its own request and
the resulting report; the set of co-requesters is never part of any response payload.

**If audits were not shareable.** Every request produces its own audit, auditor load rises in
proportion to duplicate demand, and the non-overlap constraints disappear. Nothing else in the
concurrency design changes — which is the point.

---

## A8 · Traceability via transactional outbox to an external audit service

**Decision.** Every state transition, assignment and publication is written to an **outbox table in
the same transaction as the change itself**. A relay publishes to a dedicated audit service that
owns the long-term record in its own database. No physical deletion of business records.

Full rationale and rejected alternatives: [ADR 0002](adr/0002-audit-trail.md).

**Why an external service rather than a local table.** Not volume — that would require a forecast
this system does not have. The reasons that hold at any scale:

1. The trail must **outlive the operational database's retention**. Operational data gets archived
   and pruned on a business cadence; regulatory evidence is retained on a regulatory one.
2. The trail must **not be deletable by the service that produces the facts**. Separating the
   writer from the record is what makes the record credible under inspection.
3. Retention policy, access control and inspection tooling evolve independently of the audit
   scheduling domain.

**Why transactional and not event-driven only.** If the trail is written by a downstream consumer
and the message is lost, a business fact exists with no record of it. The outbox makes divergence
impossible: the fact and its trail entry commit together or not at all. The broker is a transport
concern, never a durability one.

---

## A9 · Subscription changes are never retroactive

**Decision.** The subscription level is a property of the **request**, captured when the request is
accepted and never re-derived. Renewal, lapse, upgrade and downgrade all affect only requests
created after the change.

### A lapsed subscription does not cancel an accepted request

The commitment is created at acceptance. Once the request is in the system, the delivery date was
set by the scheduler, not chosen by the client, so withdrawing it would penalise the client for the
provider's own scheduling.

The statement's own numbers make this decisive. Essentials has a 28-day minimum window: a request
made in the final week of a subscription **can never** be delivered before that subscription
expires. If expiry cancelled the request, the last month of every Essentials subscription would be
unusable by construction.

| | Policy |
|---|---|
| Request accepted, subscription later lapses | Honoured to publication. The report remains accessible to that client. |
| Report already delivered | Remains accessible. It is the record of a fulfilled commitment. |
| New request, no valid subscription | **Rejected at validation.** |

Note the contrast with A4: *no capacity* is a system state and never produces an error, while *no
contract* is a validity condition and does. The two look similar and are not.

**Cost accepted.** The audit still consumes auditor capacity and the report reaches a client who is
no longer paying. That is a commercial trade-off, stated rather than hidden. If the business wants
the opposite, it is a policy flag on the request, not a change to the model.

### Changing level does not re-derive pending requests

Re-evaluating a pending request under a new level looks like a favour to the client and is a trap in
both directions:

| Change | What happens if re-derived | Effect |
|---|---|---|
| Upgrade (Essentials → Premium) | `max_wait` shrinks from 4 months to 1 | A request made six weeks ago would have a deadline **already in the past** — the upgrade turns a viable request `UNSCHEDULABLE` |
| Downgrade (Premium → Essentials) | `min_window` grows from 0 to 28 days | A promised access date moves **later**, breaking a commitment already communicated |

An upgrade tightens the ceiling; a downgrade pushes out the floor. Neither can be applied
retroactively without breaking something the client was already told. Freezing at acceptance is what
makes both harmless.

**Consequence for reuse (A7).** A client who upgrades gains access to more of the existing audit
inventory — but only for requests made from that point on. Audits already attached to their earlier
requests keep the terms under which those requests were accepted.
