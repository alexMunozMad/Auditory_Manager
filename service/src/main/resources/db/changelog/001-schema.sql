-- Transcription of docs/02-data-model.md §1-§6. Not an interpretation (CLAUDE.md rule 12).
-- Every table, constraint, index and CHECK carries the name the doc gives it.
-- Indexes 3-8 (docs/02 §6) are unnamed in the doc: they take a descriptive name and
-- their §6 number in a trailing comment. The updated_at trigger (§7) is 002-*.sql.


-- docs/02 §1 · catalogue and read-only tables --------------------------------

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


-- docs/02 §2 · audit --------------------------------------------------------

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


-- docs/02 §3 · audit_request ----------------------------------------------

CREATE TABLE audit_request (
    id                       uuid        PRIMARY KEY,
    client_id                uuid        NOT NULL REFERENCES client(id) ON DELETE RESTRICT,
    site_id                  uuid        NOT NULL REFERENCES site(id)   ON DELETE RESTRICT,
    audit_id                 uuid        REFERENCES audit(id)           ON DELETE RESTRICT,

    requested_at             timestamptz NOT NULL,
    subscription_level_code  text        NOT NULL,
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
    CONSTRAINT request_scheduled_has_access_date
        CHECK (status NOT IN ('SCHEDULED', 'FULFILLED') OR available_to_client_at IS NOT NULL),
    CONSTRAINT request_cancelled_has_reason
        CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);


-- docs/02 §4 · outbox_event ---------------------------------------------

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


-- docs/02 §8 · notification_dispatch ----------------------------------

CREATE TABLE notification_dispatch (
    event_id    uuid        PRIMARY KEY,
    channel     text        NOT NULL,
    recipient   text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz,
    CONSTRAINT notification_status_valid
        CHECK (status IN ('CLAIMED', 'SENT', 'FAILED'))
);


-- docs/02 §5 · the two business rules the engine enforces, plus idempotency --

ALTER TABLE audit ADD CONSTRAINT audit_one_per_auditor_per_day
    UNIQUE (auditor_id, audit_date);

CREATE UNIQUE INDEX audit_one_in_flight_per_site ON audit (site_id)
    WHERE status IN ('SCHEDULED', 'IN_PROGRESS');

CREATE UNIQUE INDEX audit_request_idempotency ON audit_request (client_id, idempotency_key);


-- docs/02 §6 · query indexes 3-8 (1 and 2 are the constraints above) --------

CREATE INDEX audit_reuse_by_site ON audit (site_id, valid_until)
    WHERE status = 'PUBLISHED';                                       -- §6 #3

CREATE INDEX audit_request_claim_queue ON audit_request (latest_audit_date)
    WHERE status = 'PENDING';                                         -- §6 #4

CREATE INDEX audit_request_by_audit ON audit_request (audit_id)
    WHERE status = 'SCHEDULED';                                       -- §6 #5

CREATE INDEX audit_request_by_client
    ON audit_request (client_id, requested_at DESC);                  -- §6 #6

CREATE INDEX outbox_event_unpublished ON outbox_event (occurred_at)
    WHERE published_at IS NULL;                                       -- §6 #7

CREATE INDEX audit_request_fulfilment_sweep
    ON audit_request (available_to_client_at)
    WHERE status = 'SCHEDULED';                                       -- §6 #8
