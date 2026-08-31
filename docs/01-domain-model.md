# 01 · Domain model

Scope is an MVP: a sound initial structure with real functionality, not a finished product. Where a
case was identified but deliberately not built, it is recorded in section 6 rather than half-solved.

The business objects carry their own behaviour — an audit knows how to compute its publication date
— rather than a service computing it on their behalf. Persistence is a separate concern.

---

## 1 · Vocabulary

| Term | Meaning |
|---|---|
| **Client** | Buys the service. Requests audits of its suppliers' sites. |
| **Supplier** | Pharmaceutical supplier. Owns one or more sites. |
| **Site** | The facility that gets audited. **Audits are facts about sites, never about clients.** |
| **Auditor** | Performs the on-site audit. |
| **Audit request** | A client's commitment: "audit this site under my subscription terms". |
| **Audit** | The visit and the resulting report. Shared by every request attached to it. |
| **Delivery window** | The interval, derived from the subscription level, in which a report must reach a client. |
| **Attach** | Bind a request to an audit — existing or newly scheduled. |
| **Access date** | When a specific client may read the report. Distinct from the audit's publication date. |

Two distinctions carry most of the design:

- **Request ≠ audit.** One is a commitment to a client; the other is a fact about a site.
- **Publication ≠ access.** The audit publishes once; each client's access is gated by its own
  minimum window.

---

## 2 · Entities

**This system writes four tables and reads two.** `AuditRequest` and `Audit` have a full lifecycle;
`Supplier` and `Site` are a catalogue it only ever inserts into (A10). `Client` and `Auditor` are
read-only.

| Written here | Owns | References by id |
|---|---|---|
| `AuditRequest` | its delivery window, its frozen subscription level, its status | `client_id`, `site_id`, `audit_id` |
| `Audit` | its date, its durations, `valid_until`, its report, its status | `site_id`, `auditor_id` |

| Catalogue · insert-only here (A10) | Holds |
|---|---|
| `Supplier` | identity, name (unique) |
| `Site` | identity, name (unique), `supplier_id` — set once, never re-parented |

| Read here | Holds |
|---|---|
| `Client` | `contact_email`, `subscription_level_code`, `subscription_valid_until` |
| `Auditor` | identity, active flag |

`Client` and `Auditor` are ordinary classes with objects: no state machine, no invariants to protect
in this scope, no endpoints that modify them. `Supplier` and `Site` are written only through one
endpoint (`POST /v1/sites`) and only ever inserted — a catalogue, not an aggregate with behaviour.

What an entity owns defines **what gets loaded and saved together**. Anything outside that boundary
is reached by query, using an id.

**A request stores `site_id`, not `(site_id, supplier_id)`.** The supplier is reachable through the
site, and storing both creates a pair that can contradict itself. Copying derivable data is only
justified when the point is to **freeze** it — which is exactly why `subscription_level` *is* copied
onto the request. Site ownership is not that case.

### The tier parameters are an enum in code, not a table

Essentials, Advanced and Premium carry `minWindowDays` and `maxWaitDays` as constants in the code,
not as rows.

**A subscription level is not data the system stores; it is a rule the system applies.** What
Essentials *means* — four weeks minimum, four months maximum — is a contractual commitment. As a
table, someone can change a delivery commitment with an `UPDATE` in production and no review. As an
enum, changing it takes a commit, a review and a deployment, which is exactly the friction a change
of that kind deserves in a regulated domain.

The join is not the argument either way: the parameters are read once, when the request is accepted,
because the derived dates are frozen from that moment (A6).

**When it becomes a table:** as soon as levels are negotiated per client. Then the terms genuinely
are data, and the enum becomes the set of defaults.

### The audit does not hold its requests

That list grows without bound — and it is precisely the part that grows, since an audit is shared
between clients. It would also force two things that change independently to be written together.
The link is navigated the other way: a request knows its audit.

### The auditor does not hold a calendar

Availability is the absence of an audit row for `(auditor_id, date)` — a question about a set of
audits, not a property of the auditor. A calendar inside `Auditor` would mean loading every future
audit to answer "is this date free", and every assignment in the system would load and lock the same
object. `Auditor` holding almost nothing is a signal the boundary is right.

### Rejected · One table for request and audit

Collapsing the two removes a join and fails on reuse. Once several clients share an audit,
`requested_at`, the frozen level, the deadline and the access date are per client, while auditor and
dates are shared. Either you keep one row per client and nominate one as "the real one" — two things
in disguise — or one row per site plus a table linking clients with their own dates, which is
`audit_request` reached the long way round.

It also breaks two things worth naming: a request can be `UNSCHEDULABLE` while the site's audit is
published and healthy, and one row cannot hold both facts; and a pending row has no auditor or date,
so `UNIQUE (auditor_id, audit_date)` degrades into an index over rows that happen to have values and
stops reading as a business rule.

---

## 3 · Where each rule is checked

**The distinction:** a rule about a single row can be checked in code, before saving. A rule about
**the relationship between several rows** cannot — the object in memory does not know what the other
rows contain, and by the time it finds out, another transaction may have changed them.

### Checked in code

| Rule | Object |
|---|---|
| The delivery window is derived once from the frozen level and never recomputed | `AuditRequest` |
| `earliest_audit_date = max(requested_at + min_window − processing_duration, tomorrow)` | `AuditRequest` |
| `latest_audit_date = requested_at + max_wait − processing_duration` | `AuditRequest` |
| A request only attaches to an audit satisfying both reuse conditions (A7) | `AuditRequest` |
| `published_at = audit_date + processing_duration_days` | `Audit` |
| `valid_until = published_at + 1 year` | `Audit` |
| A new audit is floored at the previous one's expiry (A7) | `Audit` |
| State transitions start from specific states, never from a wildcard | both |

### Enforced by the database

| Rule | Why code cannot enforce it | Mechanism |
|---|---|---|
| One auditor audits at most one site per day | Concerns every audit row for that auditor | `UNIQUE (auditor_id, audit_date)` |
| At most one audit in flight per site | Concerns every audit row for that site | partial unique index |

Two constraints, both ordinary unique indexes. Checking "is this date free" in code is a read
followed by a write, and under concurrency the gap between them is exactly where double booking
happens. No amount of care in the object closes that gap; only the database sees both transactions.

**Deliberate duplication.** Code still checks availability before picking a candidate — not for
correctness, which the constraint owns, but so the ordinary case yields a clear error instead of a
constraint violation surfacing from the driver. One layer is ergonomics, the other is correctness.

---

## 4 · `DeliveryWindow`

The one calculation that earns its own type:

```
SubscriptionLevel (an enum) ──→ DeliveryWindow (an object)

DeliveryWindow      earliestAuditDate, latestAuditDate
                    → contains(date) : boolean
                    → two flat columns in the table, one object in code
                    → frozen at acceptance, never re-derived
```

It carries the whole of A1. The translation from "report no earlier than four weeks" into an
interval of admissible audit dates happens once, in one place, and everything else only asks whether
a date falls inside it. If that rule is ever wrong, there is exactly one place to fix.

**The floor at tomorrow lives here too.** `requested_at + min_window − processing_duration` is a
date in the past for Premium (`min_window` 0, `processing` 7), so `DeliveryWindow` clamps
`earliestAuditDate` to tomorrow: no audit is ever scheduled for today or earlier. This is the rule
§6 leans on when it says a slot freed for today finds no candidate. The database mirrors only the
cheap half of it — `earliest_audit_date > (requested_at AT TIME ZONE 'UTC')::date` (§02) — as a
guard against the clamp being skipped.

Everything else is a single line of arithmetic on `Audit` and does not need a type of its own.
**`valid_until` is a plain date, not a range.** A range type earned its place only while it fed a
range constraint; with that gone, every question asked is "is it still valid" and "when does it
expire", and the start of the period is already `published_at`. A range that is never used as a
range is an expensive column.

---

## 5 · State machines

Two of them, advancing independently. That independence is what makes many requests per audit
possible.

### Audit request

```
   (accepted) → PENDING ───── attach ─────→ SCHEDULED ────→ FULFILLED
                   │                           │
                   ├── deadline passed ──→ UNSCHEDULABLE
                   │                           │
                   └────── cancelled ──────────┴──────→ CANCELLED
```

| State | Meaning |
|---|---|
| `PENDING` | Commitment accepted, no audit yet. The row is the queue. |
| `SCHEDULED` | Bound to an audit — newly scheduled or reused. The client's report has a committed date. |
| `FULFILLED` | The audit is published **and** `available_to_client_at` has passed. Terminal. |
| `UNSCHEDULABLE` | `latest_audit_date` passed with no placement. Terminal, emits an event. |
| `CANCELLED` | Withdrawn by the client. Terminal. |

`FULFILLED` does not lead to `CANCELLED`: once the report has been made available there is nothing
left to withdraw.

### Audit

```
   SCHEDULED ──→ IN_PROGRESS ──→ PUBLISHED
        │
        └── last attached request cancelled ──→ DISCARDED
```

**Why `DISCARDED` and not `CANCELLED`.** Nobody cancels an audit. A client cancels *their request*;
the audit is simply left without demand and dropped. Naming the audit's terminal state after an actor
that does not exist invites exactly the confusion of reading the diagram and asking who cancelled it.
Two different events deserve two different names in the trail.

`IN_PROGRESS` spans from the audit date to publication. The visit and the report processing are not
separate states: nothing in the system asks which of the two is currently elapsing, and the dates
already say it.

**Discarding is only reachable from `SCHEDULED`, and it is unconditional.** When the last attached
request is withdrawn the audit is discarded — no notice threshold below which it would be kept,
because an audit is never worth performing without demand merely to hold it in stock. No
configurable margin, no arbitrary number to defend.

Something has to record that the visit will not happen: otherwise the auditor stays booked for a day
nobody wants and the slot is never released. Deleting the row is not an option (A8).

The date is released as an `AuditSlotReleased` event: written to the outbox for the trail, and a
`NOTIFY` in the same transaction that wakes the worker for other pending requests — it is produced
and consumed inside this system, so it does not go out to the broker and back (A4, §04).

**"Too late to reassign" needs no rule.** A slot freed for today cannot be taken by anyone: the
earliest admissible audit date is tomorrow even for Premium (A1), so the event finds no candidate
and the day is simply lost. The window arithmetic already excludes the case.

**Demand is required to begin, not to exist.** Once the auditor has been on site the cost is spent,
so the audit proceeds to publication even with no attached request and serves whoever asks for that
site next.

**The request does not command the audit, it announces itself.** Cancellation raises an in-process
domain event, `AuditRequestCancelled`, handled in the same transaction; the audit decides for itself
whether it should follow. The two state machines stay unaware of each other, and no broker is
involved in something that happens inside one transaction.

**The reason is recorded where it varies.** The client's motive is stored on the cancelled request.
The audit's own cause is constant in this scope — no remaining demand — so a column for it would
hold a single repeated value.

### One vocabulary, two entities

`SCHEDULED` appears in both machines and that is not a collision. On the audit it means the visit has
a date; on the request it means *this client's report* has a committed date — true whether the audit
was just scheduled or was already published and the client is waiting for its access date.

There is deliberately **no second set of names for the API**. One word per concept across the whole
system: support reading the database and a client reading the response see the same state, and there
is no translation table to keep in step. Confidentiality is protected by never exposing audit
identifiers or co-requesters (§03), not by renaming states.

### The two machines are not in step, and that is the point

A request can be `SCHEDULED` against an audit already `PUBLISHED` and still not be fulfilled: an Essentials
client who requests today cannot read a report published yesterday until their 28-day window
elapses.

```
fulfilled  ⟺  audit.status = PUBLISHED  ∧  today ≥ available_to_client_at
```

Publication emits `AuditPublished`, which fulfils every attached request whose access date has
already passed; the daily sweep fulfils the rest as their dates arrive. Same principle as A4: react
to the event that changes the outcome, and keep a sweep for what only time resolves.

---

## 6 · Identified and deliberately not built

Each of these is a case the design surfaced and answered on paper. Building them is an evolution,
not a correction.

### Pulling an in-flight audit forward

An audit is scheduled to publish on 01/12/2026. A Premium client requests on 01/09/2026 with a
ceiling of 01/10/2026. The existing audit publishes too late for them, and a second audit would
overlap the site's validity (A7). Today that request stays `PENDING` and eventually expires as
`UNSCHEDULABLE`.

**The fix, when it is built:** move the in-flight audit earlier rather than create a second one. It
is safe in one direction only — `available_to_client_at = max(published_at, requested_at +
min_window)`, so an earlier publication either leaves each attached client's access date unchanged
or improves it, and no ceiling can be breached. Moving later would push every attached request out
at once.

The rule underneath: **while the audit has not occurred its date is negotiable; once it has occurred
its validity is fixed.**

Not built because it adds a third branch to the assignment worker and a minimum-notice policy toward
the auditor, in exchange for an edge case. The failure mode meanwhile is visible, not silent: the
request expires with an event.

### Auditor eligibility

Any auditor can audit any site (A3). No interface with a single pass-through implementation is
introduced for this — that would be structure without content. When qualifications appear, they
enter as a filter applied before selection, and the concurrency design is untouched: a smaller pool
does not change how competition for a date is arbitrated.

### Report structure

The report is `published_at` plus a document reference on `Audit`. It has no lifecycle of its own:
produced by one audit, published once, meaningless detached from it. If findings, versions or
signatures appear, it becomes a separate row that still belongs to its audit and is still written in
the same transaction.

### Weighted distribution

"Proportionally" is read as least loaded by audit count (A2). Selection sits behind
`AuditorSelectionPolicy`, the one interface kept, because it is the single point where an answer to
that question could change without anything else moving.
