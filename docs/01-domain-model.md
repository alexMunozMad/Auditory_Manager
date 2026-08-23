# 01 · Domain model

The business objects carry their own behaviour: an audit knows how to compute its publication date
and whether it may be moved, rather than a service computing it on its behalf. Persistence is a
separate concern.

The organising question of this document is not *what are the entities* but **where each rule is
checked**.

---

## 1 · Vocabulary

| Term | Meaning |
|---|---|
| **Client** | Buys the service. Requests audits of its suppliers' sites. |
| **Supplier** | Pharmaceutical supplier. Owns one or more sites. |
| **Site** | The physical facility that gets audited. **Audits are facts about sites, never about clients.** |
| **Auditor** | Performs the on-site audit. Occupied for `audit_duration_days`. |
| **Audit request** | A client's commitment: "audit this site under my subscription terms". |
| **Audit** | The on-site visit and the resulting report. Shared by every request attached to it. |
| **Delivery window** | The interval, derived from the subscription level, in which a report must reach a client. |
| **Attach** | Bind a request to an audit — existing or newly scheduled. |
| **Access date** | When a specific client may read the report. Distinct from the audit's publication date. |

Two distinctions carry most of the design:

- **Request ≠ audit.** One is a commitment to a client; the other is a fact about a site.
- **Publication ≠ access.** The audit publishes once; each client's access is gated by its own
  minimum window.

---

## 2 · Entities, and what each one owns

| Entity | Owns | References by id |
|---|---|---|
| `AuditRequest` | its delivery window, its frozen subscription level, its status | `client_id`, `site_id`, `audit_id` |
| `Audit` | its date, its durations, its validity period, its report, its status | `site_id`, `auditor_id` |
| `Auditor` | identity, active flag | — |
| `Client` | subscription level, valid-until date | — |

`Site` and `Supplier` are reference data: nothing in this scope modifies them.

What an entity "owns" matters because it defines **what gets loaded and saved together**. Everything
outside that boundary is reached by query, using an id, not by walking an object graph.

### The audit does not hold its requests

A list of requests inside `Audit` would grow without bound, would be loaded on every assignment, and
would force two things that change independently to be written together. Given that an audit can be
shared by many clients, that list is exactly the part most likely to grow.

The link is navigated the other way: a request knows its audit; finding the requests of an audit is
a query.

### The auditor does not hold a calendar

Tempting, and wrong. Availability is the absence of an audit row for `(auditor_id, date)` — a
question about a set of audits, not a property of the auditor. Putting the calendar inside `Auditor`
would mean loading every future audit to answer "is this date free", and every assignment in the
system would then have to load and lock the same object.

**Consequence:** `Auditor` holds almost nothing. That is a signal the boundary is in the right
place, not that something is missing.

### Rejected · One table for request and audit

Collapsing the two removes a join and a lifecycle, and fails on reuse. Once several clients share an
audit, `requested_at`, the frozen level, the deadline and the access date are per client, while
auditor and dates are shared. Either you keep one row per client and nominate one as "the real one"
— two things in disguise — or one row per site plus a table linking clients with their own dates,
which is `audit_request` reached the long way round.

It also breaks three things:

- **States diverge.** A request can be `UNSCHEDULABLE` while the site's audit is published and
  healthy. One row cannot hold both facts.
- **Pulling an audit forward loses its inputs.** Checking that no ceiling is breached needs the
  per-client ceilings.
- **Constraints weaken.** A pending row has no auditor and no date, so `UNIQUE (auditor_id,
  audit_date)` degrades into a partial index over rows that happen to have values, and stops reading
  as a business rule.

---

## 3 · Where each rule is checked

**The distinction:** a rule about a single row can be checked in code, before saving. A rule about
**the relationship between several rows** cannot — the object in memory does not know what the other
rows contain, and by the time it finds out, another transaction may have changed them. Those rules
are enforced by the database.

### Checked in code

| Rule | Object |
|---|---|
| The delivery window is derived once from the frozen level and never recomputed | `AuditRequest` |
| `latest_audit_date = requested_at + max_wait − processing_duration` | `AuditRequest` |
| A request only attaches to an audit satisfying both reuse conditions (A7) | `AuditRequest` |
| `published_at = audit_date + processing_duration_days` | `Audit` |
| `validity_period = [published_at, published_at + 1 year)` | `Audit` |
| An audit may only be moved earlier while it has not started | `Audit` |
| State transitions start from specific states, never from a wildcard | both |

### Enforced by the database

| Rule | Why code cannot enforce it | Mechanism |
|---|---|---|
| One auditor audits at most one site per day | Concerns every audit row for that auditor | `UNIQUE (auditor_id, audit_date)` |
| At most one audit in flight per site | Concerns every audit row for that site | partial unique index |
| Validity periods for a site never overlap | Same | `EXCLUDE USING gist` |

Checking "is this date free" in code is a read followed by a write, and under concurrency the gap
between the two is precisely where double booking happens. No amount of care in the object closes
that gap; only the database can, because only the database sees both transactions.

**Deliberate duplication.** Code still checks availability before picking a candidate — not for
correctness, which the constraint owns, but so the ordinary case produces a clear error instead of a
constraint violation surfacing from the driver. One layer is ergonomics, the other is correctness.
Conflating them is how systems end up with neither.

---

## 4 · Small types that carry the calculations

Rather than passing raw dates and strings around, four small types hold the arithmetic so it exists
in one place:

```
SubscriptionLevel   code, minWindowDays, maxWaitDays
                    → deliveryWindowFor(requestedAt) : DeliveryWindow

DeliveryWindow      earliestAuditDate, latestAuditDate
                    → contains(date) : boolean
                    → frozen at acceptance, never re-derived

AuditSchedule       auditDate, auditDurationDays, processingDurationDays
                    → publishedAt() : Date
                    → occupies() : DateRange

ValidityPeriod      from, until
                    → covers(date) : boolean
                    → null until publication
```

`DeliveryWindow` carries the whole of A1. The translation from "report no earlier than four weeks"
into an interval of admissible audit dates happens once, in one place, and everything else only asks
whether a date falls inside it. If that rule is ever wrong, there is exactly one place to fix.

---

## 5 · State machines

Two of them, advancing independently. That independence is what makes many requests per audit
possible.

### Audit request

```
   (accepted) → PENDING ──── attach ────→ ATTACHED ────→ FULFILLED
                   │                   (audit assigned)   (report readable
                   │                                       by this client)
                   └── deadline passed ─→ UNSCHEDULABLE
```

| State | Meaning |
|---|---|
| `PENDING` | Commitment accepted, no audit yet. The row is the queue. |
| `ATTACHED` | Bound to an audit — newly scheduled or reused. |
| `FULFILLED` | The audit is published **and** `available_to_client_at` has passed. |
| `UNSCHEDULABLE` | `latest_audit_date` passed with no placement. Terminal, emits an event. |

### Audit

```
   SCHEDULED ──→ IN_PROGRESS ──→ PROCESSING ──→ PUBLISHED
   (auditor +     (auditor        (report being    (validity
    date set)      on site)        produced)        period opens)
```

### The two are not in step, and that is the point

A request can be `ATTACHED` to an audit already `PUBLISHED` and still not be fulfilled: an Essentials
client who requests today cannot read a report published yesterday until their 28-day window
elapses. Fulfilment is therefore a function of two things — the audit's state and a date — not a
consequence of the audit's transition.

```
fulfilled  ⟺  audit.status = PUBLISHED  ∧  today ≥ available_to_client_at
```

**Where the transition is triggered.** Not by a timer per request. Publication emits `AuditPublished`,
which fulfils every attached request whose access date has already passed; the daily sweep fulfils
the rest as their dates arrive. Same principle as A4: react to the event that changes the outcome,
and keep a sweep for what only time resolves.

---

## 6 · How far an audit may be pulled forward

A7 establishes that an audit which has not happened yet can be moved earlier to satisfy a tighter
ceiling. Three bounds, and one of them is a business decision rather than a technical one:

| Bound | Source |
|---|---|
| Not before `previous_audit.valid_until + 1 day` | Validity periods must not overlap |
| Only while `status = SCHEDULED` | Once the auditor is on site, the date is a fact |
| Not within `minimum_notice_days` of today | Business courtesy toward the auditor |

The third has no basis in the statement, so it is modelled like the durations in A1: a configurable
value with a stated default (3 days), not a magic number buried in a condition. Making it explicit
turns "we don't move an auditor's visit at the last minute" from hidden behaviour into a rule
someone can change.

If no earlier date satisfies all three, the audit stays where it is and the request that prompted
the move remains `PENDING` under A4 — a capacity problem, not an error.

---

## 7 · The report

**Decision.** The report is part of `Audit`. In this scope it is `published_at` plus a document
reference; it is not a separate entity.

**Why.** It has no lifecycle of its own: produced by one audit, published once, meaningless detached
from it. Separating it would create a second thing to load, save and keep consistent, in exchange
for nothing.

**If findings, versions or signatures appear**, the report becomes a separate row that still belongs
to its audit and is still written in the same transaction. The boundary does not move; only the
shape of what sits inside it.

---

## 8 · Where the selection rules live

| Component | Responsibility |
|---|---|
| `AuditorEligibility` | Narrows the candidate pool. Currently returns every auditor (A3). |
| `AuditorSelectionPolicy` | Chooses among eligible candidates. Currently least loaded, ties by id. |
| `AuditAssignment` | The use case: reuse → pull forward → schedule. Orchestrates; holds no rules of its own. |

Eligibility and selection are separate on purpose. *Who may* is a business rule; *who should* is a
policy. Keeping them apart is what lets A3 be lifted without touching distribution, and lets
distribution be reweighted without touching eligibility.
