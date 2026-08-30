# 02 · Data model

PostgreSQL. The scheduling domain writes `audit_request`, `audit` and an outbox; `supplier` and
`site` are a catalogue it inserts into but never changes; `client` and `auditor` are read-only. The
notification component (§04) owns one more table (§8). Every index below is justified against a
concrete query; none is speculative.

---

## 1 · Catalogue and read-only tables

`client` and `auditor` are maintained outside this scope; no endpoint here modifies them.
`supplier` and `site` are a **catalogue this system writes** — `POST /v1/sites` inserts into both
(§03, A10) — but never updates or deletes.

```sql
CREATE TABLE supplier (
    id    uuid PRIMARY KEY,
    name  text NOT NULL,
    CONSTRAINT supplier_name_unique UNIQUE (name)
);

CREATE TABLE site (
    id           uuid PRIMARY KEY,
    supplier_id  uuid NOT NULL REFERENCES supplier(id) ON DELETE RESTRICT,
    name         text NOT NULL,
    CONSTRAINT site_name_unique UNIQUE (name)
);

CREATE TABLE auditor (
    id      uuid PRIMARY KEY,
    name    text NOT NULL,
    active  boolean NOT NULL DEFAULT true
);

CREATE TABLE client (
    id                        uuid PRIMARY KEY,
    name                      text NOT NULL,
    contact_email             text NOT NULL,
    subscription_level_code   text NOT NULL,
    subscription_valid_until  date NOT NULL,
    CONSTRAINT client_level_valid
        CHECK (subscription_level_code IN ('ESSENTIALS', 'ADVANCED', 'PREMIUM'))
);
```

**`supplier.name` and `site.name` are unique.** A supplier is identified by its name, so
`POST /v1/sites` resolves `{ supplier: { name } }` to the existing row rather than duplicating it. A
site name is treated as a globally unique facility identifier: a site belongs to exactly one
supplier and is never re-parented, so creating an existing site name under a different supplier is a
`409` (§03). *(Assumption: site names are facility-unique. If two suppliers can each hold a site of
the same name, this becomes `UNIQUE (supplier_id, name)` with an immutable `supplier_id`.)*

**`contact_email` on `client`.** The notification component (§04) needs a destination for the
assignment and report-ready emails. It is part of the client record, maintained with the rest of it;
this system only reads it.

The subscription level is a code, not a foreign key: the tier parameters are an enum in code (§01).
The `CHECK` keeps the column honest without a table whose only purpose is to be joined.

---

## 2 · `audit`

```sql
CREATE TABLE audit (
    id                        uuid        PRIMARY KEY,
    site_id                   uuid        NOT NULL REFERENCES site(id)    ON DELETE RESTRICT,
    auditor_id                uuid        NOT NULL REFERENCES auditor(id) ON DELETE RESTRICT,

    audit_date                date        NOT NULL,
    audit_duration_days       int         NOT NULL DEFAULT 1,
    processing_duration_days  int         NOT NULL DEFAULT 7,

    published_at              date,
    valid_until               date,
    report_uri                text,

    status                    text        NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT audit_status_valid
        CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'PUBLISHED', 'DISCARDED')),
    CONSTRAINT audit_durations_positive
        CHECK (audit_duration_days > 0 AND processing_duration_days >= 0),
    CONSTRAINT audit_validity_paired
        CHECK ((published_at IS NULL) = (valid_until IS NULL)),
    CONSTRAINT audit_validity_after_publication
        CHECK (valid_until IS NULL OR valid_until > published_at),
    CONSTRAINT audit_published_has_date
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT audit_published_has_report
        CHECK (status <> 'PUBLISHED' OR report_uri IS NOT NULL)
);
```

**`auditor_id` is `NOT NULL`.** An audit row only comes into existence when the worker has already
chosen an auditor and a date; a request without an audit is a `PENDING` row in the other table. There
is no "audit awaiting an auditor" state to model, which removes a nullable column and a whole class
of half-built rows.

**`audit_date` is `date`, not `timestamptz`.** "One auditor audits at most one site per day" is a
calendar rule. With a timestamp, two rows one hour apart would be distinct values and the unique
index would not catch the conflict. The type is doing part of the enforcement.

**`valid_until` is stored, not generated.** It is written at publication by the domain object, which
is where the calendar-year arithmetic and the leap-day rule already live (A7). A generated column
would put the same rule in a second place.

**`valid_until` is not tied to `published_at` by a `CHECK`.** `audit_validity_paired` and
`audit_validity_after_publication` keep the two columns paired and ordered, but the exact rule —
twelve calendar months, with 29/02 mapping to 28/02 (A7) — lives only in the domain object.
Encoding it as a `CHECK` would duplicate the calendar-year arithmetic; a loose bounds check
(`between published_at + 360d and + 370d`) would introduce an arbitrary number to defend. This is
the same call made when the range-exclusion constraint was dropped: **the arithmetic is a test's
job, not a constraint's.** Written here so it reads as a decision, not an omission.

**A `PUBLISHED` audit must carry a report.** `audit_published_has_report` mirrors
`audit_published_has_date`: the row that represents a published audit is inconsistent without the
document reference it exists to hold. `DISCARDED` is only reachable from `SCHEDULED` (§01), so no
non-published state ever has a `report_uri` to check against.

---

## 3 · `audit_request`

```sql
CREATE TABLE audit_request (
    id                       uuid        PRIMARY KEY,
    client_id                uuid        NOT NULL REFERENCES client(id) ON DELETE RESTRICT,
    site_id                  uuid        NOT NULL REFERENCES site(id)   ON DELETE RESTRICT,
    audit_id                 uuid        REFERENCES audit(id)           ON DELETE RESTRICT,

    requested_at             timestamptz NOT NULL,
    subscription_level_code  text        NOT NULL,  -- frozen copy: the level in force when the
                                                   -- request was accepted (A6, A9). Never
                                                   -- re-derived from client.subscription_level_code.
    earliest_audit_date      date        NOT NULL,
    latest_audit_date        date        NOT NULL,
    available_to_client_at   date,

    status                   text        NOT NULL,
    cancellation_reason      text,
    idempotency_key          text        NOT NULL,

    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT request_status_valid
        CHECK (status IN ('PENDING', 'SCHEDULED', 'FULFILLED', 'UNSCHEDULABLE', 'CANCELLED')),
    CONSTRAINT request_window_ordered
        CHECK (earliest_audit_date <= latest_audit_date),
    CONSTRAINT request_window_starts_in_future
        CHECK (earliest_audit_date > (requested_at AT TIME ZONE 'UTC')::date),
    CONSTRAINT request_attached_has_audit
        CHECK (status NOT IN ('SCHEDULED', 'FULFILLED') OR audit_id IS NOT NULL),
    CONSTRAINT request_cancelled_has_reason
        CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);
```

**The three frozen columns are the point of this table.** `subscription_level_code`,
`earliest_audit_date` and `latest_audit_date` are resolved once, when the request is accepted, and
never recomputed (A6, A9). They are the contractual commitment; everything else about the request is
derived from them.

**`request_window_starts_in_future` is a sanity guard, not the window rule.** The window itself is
resolved in the domain object (§01):

```
earliest_audit_date = max(requested_at + min_window − processing_duration, tomorrow)
latest_audit_date   = requested_at + max_wait − processing_duration
```

The `CHECK` only asserts the cheap invariant that the earliest admissible date is after the day the
request arrived. It catches a broken floor — Premium's raw `requested_at + 0 − 7` is a week in the
past before the `max(…, tomorrow)` clamp is applied — without re-encoding the arithmetic. The
`AT TIME ZONE 'UTC'` pins the cast to a fixed zone so it does not drift with the session's `TimeZone`
setting; a one-day fuzz at the boundary is irrelevant to a guard whose job is to catch a
week-in-the-past.

**`latest_audit_date` is frozen with the default `processing_duration_days` (7).** In this scope the
duration never varies from the default, so this request-time arithmetic and the audit-time
`published_at = audit_date + processing_duration_days` agree. If the duration becomes genuinely
variable per audit, `latest_audit_date` must be recomputed against the audit's actual value — or the
placement and reuse checks must read it from the audit rather than assume the default. Identified,
not built (A1).

**`requested_at` is `timestamptz`, the window columns are `date`.** The instant of acceptance is a
point in time; the scheduling window is a set of calendar days. Different questions, different types.

**Cancelling requires a reason.** In a regulated domain, knowing that something was withdrawn
without knowing why is half an answer. The constraint makes it impossible to record half.

No client-facing endpoint reaches `CANCELLED` (§03) — cancellation, and audit discard, are modelled
and reachable only through an operations path. That is exactly why the invariant belongs in the
database: the one writer that *does* reach these states is a hand-run script or console, one
forgotten `SET cancellation_reason` away from a half-recorded withdrawal. The state machine is
complete; the HTTP surface is a deliberate subset, not the edge of the model.

**`available_to_client_at` is stored although it is derivable.** It could be computed as
`max(audit.published_at, requested_at + min_window)` at query time. It is stored because it is the
key of the fulfilment sweep:

```sql
SELECT ar.id
  FROM audit_request ar
  JOIN audit a ON a.id = ar.audit_id
 WHERE ar.status = 'SCHEDULED'
   AND ar.available_to_client_at <= CURRENT_DATE
   AND a.status = 'PUBLISHED';
```

Index 8 narrows `audit_request` to the candidate set; the join to `audit` is by primary key. The
computed form would be a `GREATEST` over a join that no index serves.

**`a.status = 'PUBLISHED'` is not optional.** `available_to_client_at` is set at attach from the
*projected* publication date, so if the audit slips and stays `IN_PROGRESS`, that date can pass
while no report exists. The sweep must not fulfil a request whose audit has not published — the rule
is `fulfilled ⟺ audit PUBLISHED ∧ today ≥ available_to_client_at` (§01).

**`available_to_client_at` is reconciled once, at publication.** The value written at attach is a
projection. When `AuditPublished` fires, the handler recomputes it as
`max(actual published_at, requested_at + min_window)` for each attached request, then fulfils those
already due. It is corrected once, when reality lands — not recalculated on every read. The frozen
contractual ceiling (`latest_audit_date`, and the `reportNoLaterThan` the client was told) does not
move; only the estimate does.

---

## 4 · `outbox_event`

```sql
CREATE TABLE outbox_event (
    id              uuid        PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);
```

**`aggregate_id` deliberately has no foreign key.** It must be able to record facts about rows that
are one day archived. An event that disappears along with the row it describes is worthless as
evidence (A8).

---

## 5 · Constraints that enforce business rules

Two, both ordinary unique indexes.

```sql
-- One auditor audits at most one site per day
ALTER TABLE audit ADD CONSTRAINT audit_one_per_auditor_per_day
    UNIQUE (auditor_id, audit_date);

-- At most one audit in flight per site
CREATE UNIQUE INDEX audit_one_in_flight_per_site ON audit (site_id)
    WHERE status IN ('SCHEDULED', 'IN_PROGRESS');
```

The first is the rule stated in the problem, enforced where application logic cannot bypass it. The
second prevents the only real race on the site calendar: two workers creating an audit for the same
site at the same moment.

Non-overlap of published validity periods is **not** a third constraint. With at most one audit in
flight per site, and each new audit floored at the previous one's expiry, an overlap can only come
from miscomputed arithmetic — which is a test's job (A7).

### Idempotency

```sql
CREATE UNIQUE INDEX audit_request_idempotency ON audit_request (client_id, idempotency_key);
```

Infrastructure, not a business rule, which is why it sits apart from the two above. It is what makes
`POST /v1/audit-requests` safe to retry: a client that times out and retries must not end up with two
commitments and two booked auditors. Scoped per client so keys cannot collide across tenants.

### Considered and not added

`UNIQUE (client_id, site_id) WHERE status = 'PENDING'` would stop a client queueing the same site
twice. Left out because duplicate submissions are already handled by the `Idempotency-Key` on the
endpoint, and a second pending request for the same site is harmless: both attach to the same audit.
A constraint that protects nothing is a constraint to explain later.

---

## 6 · Indexes, each against a query

| # | Index | The query it serves |
|---|---|---|
| 1 | `UNIQUE (auditor_id, audit_date)` | Constraint. Also answers "is this auditor free on this date". |
| 2 | `UNIQUE (site_id) WHERE status IN ('SCHEDULED','IN_PROGRESS')` | Constraint. Also answers "does this site have an audit in flight". |
| 3 | `(site_id, valid_until) WHERE status = 'PUBLISHED'` | Reuse check, published branch: the current valid audit for a site. Also the floor at the previous audit's expiry (A7). |
| 4 | `(latest_audit_date) WHERE status = 'PENDING'` | The worker's claim query, ordered by deadline. |
| 5 | `(audit_id) WHERE status = 'SCHEDULED'` | Fan-out on `AuditPublished`; counting remaining requests when one is cancelled. |
| 6 | `(client_id, requested_at DESC)` | `GET /audit-requests` for a client. |
| 7 | `(occurred_at) WHERE published_at IS NULL` | The relay's poll for unpublished events. |
| 8 | `(available_to_client_at) WHERE status = 'SCHEDULED'` | The daily fulfilment sweep: scheduled requests whose access date has arrived. |

**Six of eight are partial.** Each excludes rows that the query never looks at — resolved requests,
cancelled audits, already-published events — so the indexes stay small no matter how large the
tables grow. Index 7 is the clearest case: with a relay that keeps up, it holds a handful of rows
however many millions the table accumulates.

**Index 8 is the fulfilment sweep's key.** Index 5 is `(audit_id) WHERE status = 'SCHEDULED'` — it
serves the fan-out on `AuditPublished` (given an audit, its attached requests) and cannot serve the
sweep, which scans every scheduled request by date. Different leading column, separate index. It is
partial and tiny: only requests already attached and waiting for their own access date to arrive.
The sweep still joins `audit` to require `PUBLISHED` (§3); index 8 narrows the set, the join is
by PK.

**The reuse check (A7) is two reads, not one.** An in-flight audit — `SCHEDULED` or `IN_PROGRESS` —
is also a reuse candidate, but its `valid_until` is `NULL` until publication (`audit_validity_paired`),
so index 3 cannot answer for it. It does not have to. Index 2 is a partial *unique* index, so the
site has **at most one** in-flight audit, and index 2 returns it directly; its validity is projected
in code (`audit_date + processing_duration_days + 1 year`). Index 3 returns the current published
audit with its real `valid_until`. The code checks each against the two A7 conditions. Index 2 earns
a second use and the apparent gap closes — which is why index 3's predicate is `status = 'PUBLISHED'`,
not `status <> 'DISCARDED'`: the non-published rows it used to hold were `NULL` entries nobody reads.

### The one query no index serves well

When capacity is released on date D, the worker looks for pending requests whose window contains D:

```sql
WHERE status = 'PENDING' AND earliest_audit_date <= D AND latest_audit_date >= D
```

A B-tree cannot serve containment on two columns; index 4 narrows to pending rows and the second
predicate is a filter. This is acceptable because the pending set is bounded — requests leave it as
soon as they are placed — and it is honest to say so rather than add an index that would not help.

The range-aware answer would be a `daterange` column with a GiST index. Deliberately not taken: it
introduces a type and an index method to optimise a scan over a small set.

---

## 7 · Conventions

**No physical deletion.** Every foreign key is `ON DELETE RESTRICT`. A `CASCADE` anywhere would
contradict the traceability policy (A8).

**No optimistic-locking column.** There is no `version` on `audit` or `audit_request`. Every row is
written by a single writer by construction: the assignment worker owns each `audit_request`
transition out of `PENDING` and the whole `audit` lifecycle, and the publication endpoint touches
only `IN_PROGRESS` audits the worker has already let go of (transitions start from named states,
never a wildcard — §01, §03). Two transactions never race to write the same row. Where concurrency
is real — two requests competing for a slot, or a future second worker — the arbiter is a unique
index, not a version check. Full rationale: [ADR 0003](adr/0003-no-optimistic-concurrency-control.md).

**Cost accepted.** If assignment is ever parallelised per site, row-level races on `audit` become
possible and a `version` column or `SELECT … FOR UPDATE` has to be added. The partial index
`audit_one_in_flight_per_site` still prevents concurrent in-flight audits until then.

**UUIDv7 identifiers**, time-ordered, so inserts land at the end of the B-tree instead of scattering
and splitting pages. Cost accepted: the identifier leaks its creation instant, which is acceptable
here and would not be for a public identifier of a sensitive resource.

**State machines are an enum in code and a `CHECK` in the database — never a table.** Same reasoning
as the subscription levels: a state is not data the system stores, it is the code's control flow. A
states table adds a join and lets someone insert a value the code does not know how to handle.

A Postgres `ENUM` type is avoided too: adding a value is a migration with awkward transactional
behaviour and values cannot be removed, whereas changing a `CHECK` is an ordinary migration. The set
of states is expected to grow — `DISCARDED` on the audit already arrived late.

**`updated_at` is maintained by a trigger, not by the application.** Both written tables carry a
`BEFORE UPDATE` trigger calling one shared function:

```sql
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_set_updated_at         BEFORE UPDATE ON audit
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER audit_request_set_updated_at BEFORE UPDATE ON audit_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

An `@UpdateTimestamp` on the ORM entity would leave the column stale whenever the operations path
writes a row by hand — the same path that reaches `CANCELLED` and `DISCARDED`. The trigger keeps the
column honest whatever the writer, for the same reason the invariants sit in the database.

**Auditor workload is computed, never stored.** No counter column on `auditor`.

```sql
SELECT a.id, count(x.id) AS load
  FROM auditor a
  LEFT JOIN audit x ON x.auditor_id = a.id
       AND x.audit_date >= CURRENT_DATE - INTERVAL '90 days'
       AND x.status <> 'DISCARDED'
 WHERE a.active
 GROUP BY a.id
 ORDER BY load, a.id;
```

A stored counter fails on its own terms: **a lifetime total cannot express a rolling window**. A
long-serving auditor would carry a permanently high count and stop receiving assignments until the
others caught up. Fairness needs rows counted within a date range, which is a query.

It would also be a second copy of a fact already recorded in the audit rows, and the contention point
that the single-writer worker exists to avoid (ADR 0001). The index it needs already exists:
`UNIQUE (auditor_id, audit_date)` serves both the constraint and this aggregate.

---

## 8 · Outside the scheduling domain

The notification component (§04) keeps its own table for delivery idempotency. It is not part of the
scheduling model — listed here only so the schema is complete.

```sql
CREATE TABLE notification_dispatch (
    event_id    uuid        PRIMARY KEY,   -- the outbox event that triggered the send
    channel     text        NOT NULL,      -- 'email'
    recipient   text        NOT NULL,
    status      text        NOT NULL,      -- CLAIMED | SENT | FAILED
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz,
    CONSTRAINT notification_status_valid
        CHECK (status IN ('CLAIMED', 'SENT', 'FAILED'))
);
```

`event_id` as primary key **is** the concurrency control: N consumer instances race with
`INSERT … ON CONFLICT (event_id) DO NOTHING`, and only the row's creator sends the email. Delivery
is at-least-once — a crash between sending and marking `SENT` resends — so a rare duplicate is
possible, a lost notification is not. See §04.
