# 03 · API contracts · HTTP

Two audiences, two contracts. The **client API** is what a paying client calls. The **internal API**
is what operations calls. They are separate because they have different threat models and different
vocabularies, not because of URL cosmetics.

Base: `/v1`. Version in the path — simplest to route, log and reproduce from a terminal. Header-based
negotiation is more elegant and harder to debug, which is the wrong trade this early.

The asynchronous side — the audit trail, the notification emails, client webhooks — is
[`04 · Event contracts`](04-event-contracts.md).

---

## Endpoints at a glance

| Method | Path | Audience | Purpose |
|---|---|---|---|
| `POST` | `/v1/sites` | Client | Register a site, and its supplier if new |
| `POST` | `/v1/audit-requests` | Client | Raise an audit request |
| `GET` | `/v1/audit-requests/{id}` | Client | One request, projected across both tables |
| `GET` | `/v1/audit-requests` | Client | The client's requests, cursor-paged |
| `GET` | `/v1/audit-requests/{id}/report` | Client | Stream the report once accessible |
| `POST` | `/internal/v1/audits/{id}/publication` | Internal | Attach the report, publish the audit |

---

## 1 · What the client can see

A client sees **its own request**, with the report inside it. There is no `/audits` resource on the
client API.

This is not a simplification, it is the confidentiality rule of A7 expressed as a shape. An audit is
shared by every client that attached to it; exposing it as a resource would create an identifier
that two clients could both hold and correlate. Without the resource, the question cannot be asked.

**The states are the same on both sides.** There is no client-facing vocabulary distinct from the
internal one:

| State | Meaning to the client |
|---|---|
| `PENDING` | We are finding a slot |
| `SCHEDULED` | Your report has a committed date |
| `FULFILLED` | The report is ready for you |
| `UNSCHEDULABLE` | We could not meet the commitment |
| `CANCELLED` | Withdrawn |

A translation layer between internal and external names was considered and rejected. It protects
nothing — confidentiality comes from never exposing an audit identifier, not from renaming a state —
and it costs a mapping that support has to perform mentally every time it compares a client's screen
with the database. One word per concept, everywhere.

**The site catalogue is not confidential.** Any client can register and target any site (A10);
`site` and `supplier` names are shared platform data. What stays private is every client's *request*
and the audit behind it.

---

## 2 · `POST /v1/sites`

Register a site so a request can target it. The frontend decides whether the supplier already
exists; the endpoint takes it either way.

```http
POST /v1/sites
Authorization: Bearer <token>
Content-Type: application/json

{ "name": "Warehouse 7", "supplier": { "id": "018f3a2c-...-1000" } }
```
```json
{ "name": "Warehouse 7", "supplier": { "name": "Acme Pharma SL" } }
```

**One endpoint, flexible supplier.** `supplier.id` uses an existing supplier. `supplier.name`
resolves to an existing supplier of that name, or creates one. Both present → `id` wins. Neither →
`422`.

### `201 Created` / `200 OK`

```json
{
  "id": "018f7a10-...-2af0",
  "name": "Warehouse 7",
  "supplier": { "id": "018f3a2c-...-1000", "name": "Acme Pharma SL" }
}
```

`201` when the site is created, `200` when it already existed under this supplier — the call is
idempotent on `(name, supplier)`.

**Names are unique.** `supplier.name` is unique, so a second "Acme Pharma SL" is never created; the
existing row is used. `site.name` is unique globally: a site belongs to exactly one supplier and is
never re-parented.

| Status | Type | When |
|---|---|---|
| `200` | — | Site already exists under this supplier |
| `201` | — | Created |
| `404` | `supplier-not-found` | `supplier.id` does not exist |
| `409` | `site-name-taken` | A site with this name exists under a different supplier |
| `422` | `validation-failed` | No supplier reference, or malformed body |

*Assumption: a site name is a facility-unique identifier. If two suppliers can each hold a site of
the same name, the uniqueness becomes `(supplier_id, name)` and this `409` goes away — see §02.*

---

## 3 · `POST /v1/audit-requests`

Create a request. Assignment happens later (ADR 0001).

```http
POST /v1/audit-requests
Authorization: Bearer <token>
Idempotency-Key: 8f14e45f-ea2b-4f0e-9b7a-1c2d3e4f5a6b
Content-Type: application/json

{ "siteId": "018f3a2c-...-9d21" }
```

**One field.** The client is known from the token, the subscription level from the client, and the
dates are a consequence of that level. A client does not propose a date, so the contract does not
offer a way to try.

### `201 Created`

```json
{
  "id": "018f5b71-...-4c02",
  "siteId": "018f3a2c-...-9d21",
  "status": "PENDING",
  "subscriptionLevel": "ESSENTIALS",
  "requestedAt": "2026-08-26T10:15:00Z",
  "commitment": {
    "reportNoEarlierThan": "2026-09-23",
    "reportNoLaterThan": "2026-12-24"
  },
  "expectedReportDate": null,
  "report": null
}
```

**`201`, not `202`.** `202 Accepted` says the request was received and a resource may or may not
exist. Here the resource definitely exists, is addressable and carries a contractual commitment. The
work being incomplete is expressed by `status`, which is more precise than a status code that has to
be interpreted.

**The commitment is stated in the client's terms.** Internally the frozen columns are
`earliest_audit_date` and `latest_audit_date`; the client is told about report dates, because that
is what the subscription promises and what the problem statement talks about. The API projects back
across the processing duration.

**`subscriptionLevel` is the frozen value.** The response echoes the level recorded on the request
(A9), not the client's current level — they differ after an upgrade, and the request keeps the terms
it was accepted under.

**`expectedReportDate` is null until a slot exists.** Once assigned it holds `available_to_client_at`
— the promise for *this* client, not the audit's publication date. It is set from the *projected*
publication date at assignment and can move later once, if the audit slips in processing: it is
reconciled to the actual date at publication (§02). `commitment.reportNoLaterThan` never moves —
that is the contract; `expectedReportDate` is the current estimate, observable only by polling in
this scope (A5).

### Idempotency

`Idempotency-Key` is required. The key is stored with the request.

| Case | Result |
|---|---|
| Same key, same body, within 24h | The original `201` response, replayed |
| Same key, different body | `422` · `idempotency-key-reused` |
| Missing key | `400` · `idempotency-key-required` |

A retry after a timeout must not create a second commitment and a second audit. This is the endpoint
where that matters most, because the side effect is a booked auditor.

### Failures

| Status | Type | When |
|---|---|---|
| `400` | `validation-failed` | Malformed body, unknown field, missing key |
| `404` | `site-not-found` | No such site |
| `422` | `subscription-not-active` | `subscription_valid_until` has passed (A9) |

**Any client can request any site (A10).** There is no `403 site-not-accessible`: audits are shared
facts about sites, so scoping requests to a client's own suppliers would fight the sharing model. An
unknown `siteId` is a plain `404`.

**No status code means "no capacity".** A request that cannot be placed is still accepted and
returns `201` with `PENDING` (A4). The maximum wait is a commitment, not a validity condition, and
turning a capacity shortage into a client-side error would discard the record that demand existed.

`422` for an inactive subscription rather than `403`: the caller is authenticated and authorised to
use the API, and the body is well-formed. What fails is a business precondition. `403` is defensible
and was rejected because it would conflate "you may not call this" with "your contract has lapsed" —
two things support needs to tell apart.

---

## 4 · `GET /v1/audit-requests/{id}`

The projection across both tables. This endpoint is where the two-table split is paid for.

```json
{
  "id": "018f5b71-...-4c02",
  "siteId": "018f3a2c-...-9d21",
  "status": "FULFILLED",
  "subscriptionLevel": "ESSENTIALS",
  "requestedAt": "2026-08-26T10:15:00Z",
  "commitment": {
    "reportNoEarlierThan": "2026-09-23",
    "reportNoLaterThan": "2026-12-24"
  },
  "expectedReportDate": "2026-09-23",
  "report": {
    "availableSince": "2026-09-23",
    "url": "/v1/audit-requests/018f5b71-...-4c02/report"
  }
}
```

**No audit identifier appears anywhere.** The report is addressed through the request that earned
access to it. Two clients attached to the same audit hold two different URLs to the same document,
and neither can discover the other.

**`404` rather than `403` when the request belongs to another client.** Confirming that an id exists
is itself information.

When the state is `UNSCHEDULABLE`, a `reason` field explains it. That is the only case where the
client learns something went wrong, and it must not be silent. In this scope `reason` is always
`deadline-passed-without-placement` — the single cause the model produces: `latest_audit_date`
arrived with no free `(auditor, date)` pair left in the window. It is a constant emitted at
serialization, not a stored column, for the same reason the discarded audit carries no reason column
(§02 · one repeated value is not data). When a second cause appears it becomes a stored value.

---

## 5 · `GET /v1/audit-requests`

```http
GET /v1/audit-requests?status=SCHEDULED&status=PENDING&siteId=...&limit=20&cursor=<opaque>
```

```json
{
  "items": [ ... ],
  "nextCursor": "eyJyZXF1ZXN0ZWRBdCI6..."
}
```

| Parameter | Contract |
|---|---|
| `status` | Optional. Repeatable — multiple values are OR'd. Only the five request states are accepted. |
| `siteId` | Optional. Exact match. |
| `limit` | Optional. Default 20, maximum 100. |
| `cursor` | Optional. Opaque. An invalid or expired cursor is `400` · `invalid-cursor`, never a silent reset. |

**Fixed sort: `requested_at DESC, id DESC`.** Not client-selectable. It matches index 6 exactly and
is what the cursor encodes.

**Cursor, not offset.** New requests arrive at the head of the list, so an offset shifts underneath
a client paging through it and rows get skipped or repeated. The cursor encodes `(requested_at, id)`.

---

## 6 · `GET /v1/audit-requests/{id}/report`

**`200` with the document, streamed.** The endpoint reads `audit.report_uri` — the internal storage
pointer (`s3://…`) — and streams the bytes back under the caller's own authorisation, checked on
every request. No redirect, no signed URL: nothing addressable leaves the auth perimeter, which is
the right default for a regulated report. Cost accepted: the application proxies the bytes, which is
nothing for a PDF at this volume.

| Status | When |
|---|---|
| `200` | The request is `FULFILLED` |
| `409` · `report-not-yet-available` | Attached, but not `FULFILLED`: the access date has not arrived, **or** the audit has not published |
| `404` | Any other state, or another client's request |

**`409` is the interesting one.** The report may physically exist — published for another client
weeks ago — and still not be available to this one. The status code says "not yet", not "not found",
because "not found" would be a lie the client could later disprove.

---

## 7 · Internal API

Not reachable by clients. **Network-isolated** — not internet-facing — and called with a service
credential: mutual TLS, or the OAuth client-credentials grant. Not the focus of the exercise; stated
so the boundary is explicit rather than assumed.

### `POST /internal/v1/audits/{id}/publication`

```json
{ "reportUri": "s3://reports/018f7c3d-...-1a44.pdf" }
```

Moves the audit to `PUBLISHED`, sets `published_at` and `valid_until`, emits `AuditPublished`, and
reconciles `available_to_client_at` on every attached request against the actual publication date
(§02), fulfilling those already due.

| Status | Type | When |
|---|---|---|
| `200` | — | Published — or re-called with the same `reportUri` on an already-published audit |
| `409` | `audit-already-published` | Re-called with a different `reportUri` |
| `409` | `audit-not-in-progress` | The audit is `SCHEDULED` or `DISCARDED` |
| `422` | `validation-failed` | Missing or malformed `reportUri` |

**Idempotent on retry.** The caller is an external system uploading a file; a network timeout after
the server has committed must be safe to retry. The audit's state is the dedup key — already
`PUBLISHED` with the same `reportUri` replays `200` and emits nothing; a different `reportUri` is a
conflict. No `Idempotency-Key` header is needed.

**Publication is an endpoint because it carries data from outside the system** — the report itself.
`SCHEDULED → IN_PROGRESS` is *not* an endpoint, because nothing arrives with it: the audit date
passes and the daily sweep advances the state. Only transitions that need input from outside get a
verb.

### Not in this scope

Cancelling a request has no endpoint. `CANCELLED` on the request and `DISCARDED` on the audit are
modelled and reachable only through an operations path that is not part of this contract. The state
machine is ready; the API surface is deliberately not — and the invariants those states carry (a
cancellation always records a reason) sit in the database precisely because the only writer that
reaches them is an operations script, not this contract (§02).

---

## 8 · Errors

RFC 9457 `application/problem+json` throughout:

```json
{
  "type": "https://api.qualifyze.com/problems/subscription-not-active",
  "title": "Subscription is not active",
  "status": 422,
  "detail": "The subscription for this client expired on 2026-08-01.",
  "instance": "/v1/audit-requests"
}
```

A stable `type` URI is what clients branch on. Reason phrases and `detail` text are free to change;
`type` is part of the contract.

---

## 9 · How the client learns that something changed

**Polling** `GET /v1/audit-requests/{id}` is the synchronous answer. Assignment typically completes
in seconds and publication is weeks away, so a client polls twice: once shortly after creating the
request, once around the committed date.

**Notifications.** In parallel, the notification component emails the client on three events —
request received, audit assigned, report ready — consumed from the outbox. That path, its payloads
and its concurrency control are [`04 · Event contracts`](04-event-contracts.md).

**Webhooks** are the obvious evolution and are already paid for: the events exist in the outbox
(A8), so delivering them outward is a consumer, not a new mechanism.
