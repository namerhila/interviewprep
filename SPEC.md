# Spec — Pet Insurance Quotes (Lemonade home assignment)

## 1. Overview

Customers get a monthly price quote for pet insurance before they buy a policy.
Build a small backend service with two endpoints:

| # | Endpoint | Purpose |
|---|----------|---------|
| 1 | `POST /quotes` | Create a quote for a pet |
| 2 | `GET /quotes/{id}` | Get a quote by its id |

The reviewers will run curl requests against the server. They expect
production-quality code and a thoughtful test suite.

## 2. Request

`POST /quotes`, header `Content-Type: application/json`:

```json
{
  "petType": "DOG",
  "breed": "Pug",
  "birthDate": "2023-05-10",
  "plan": "PREMIUM",
  "deductible": 250,
  "country": "US"
}
```

| Field | Type | Required | Allowed values | Design note |
|-------|------|----------|----------------|-------------|
| `petType` | string (enum) | Yes | `DOG`, `CAT` (exact case) | Enum, easy to add new pet types |
| `breed` | string | No | Free text, see breed rules | Validate length and characters |
| `birthDate` | string, ISO date `yyyy-MM-dd` | Yes | A real calendar date; pet age must be in range (section 4) | |
| `plan` | string (enum) | Yes | `BASIC`, `PREMIUM` (exact case) | Enum, easy to add new plans |
| `deductible` | JSON number (USD) | Yes | `100`, `250`, `500` | `BigDecimal`, never `double`. `100.00` is accepted and normalized to `100` |
| `country` | string | Yes | `US` only (case-sensitive: `"us"` is rejected) | Open for extension: country → currency mapping, add countries later without changing existing code |

- **Unknown fields** (e.g. `"color": "black"`) are ignored.

**Breed rules** (when present):
- The value is trimmed, then stored and returned trimmed (`"  Pug "` → `"Pug"`).
- Trimmed length is 1–50 characters.
- Only English letters `A–Z a–z`, spaces, hyphens (`-`) and apostrophes (`'`). Accented letters (`"Löwchen"`) are rejected.

## 3. Response

`POST` returns `201 Created` with an absolute `Location` header (e.g. `http://localhost:8080/quotes/{id}`).
`GET /quotes/{id}` returns `200` with the same body. Only `status` can differ, because it is computed at read time.

```json
{
  "id": "3f1c…-uuid",
  "petType": "DOG",
  "breed": "Pug",
  "birthDate": "2023-05-10",
  "plan": "PREMIUM",
  "deductible": 250,
  "country": "US",
  "monthlyPremium": 49.14,
  "currency": "USD",
  "createdAt": "2026-11-19T10:00:00Z",
  "validUntil": "2026-12-19T10:00:00Z",
  "status": "ACTIVE"
}
```
- `id` is a server-generated UUID.
- `monthlyPremium` has exactly 2 decimals (e.g. `20.00`).
- `createdAt` and `validUntil` are UTC instants. `validUntil` = `createdAt` + 30 days. Time comes from the injected `Clock`.
- `status` is `ACTIVE` while now < `validUntil`, and `EXPIRED` from `validUntil` on. It's computed on every response and never stored.

## 4. Business rules

### 4.0 Business time zone

- Calendar-date rules (the pet's age) use **today's date in the business time zone**. The zone comes from the configurable property `quote.business-zone`, default `America/New_York`.
- Timestamps (`createdAt`, `validUntil`) stay UTC instants, unaffected by the zone.
- Example: at `2026-11-20T03:00Z` it is still `2026-11-19` in New York, so that's "today".

### 4.1 Pricing
The monthly premium is in USD. Start from the plan's base price, then multiply by every factor:

| Factor | Rule |
|--------|------|
| Base price | `BASIC` 20.00, `PREMIUM` 35.00 |
| Pet age (full years) | under 1 → ×1.0 · 1–7 → ×1.2 · 8 and up → ×1.6 |
| High-risk breed | ×1.3 for French Bulldog, Bulldog, Pug, Persian (case-insensitive, trimmed, whole-name match: "Bulldog Mix" is **not** high-risk) |
| Deductible | 100 → ×1.0 · 250 → ×0.9 · 500 → ×0.8 |

- Do all calculations in `BigDecimal` and round **once, at the end**, to 2 decimals using `HALF_UP`.
- Keep the pricing rules in one place, so changing a factor doesn't touch the service flow.

Worked examples:

| Plan | Age | Breed | Deductible | Calculation | Premium |
|------|-----|-------|-----------|-------------|---------|
| PREMIUM | 3y | Pug | 250 | 35 × 1.2 × 1.3 × 0.9 | **49.14** |
| BASIC | 6 months | (none) | 100 | 20 × 1.0 × 1.0 × 1.0 | **20.00** |
| BASIC | 8y | persian | 500 | 20 × 1.6 × 1.3 × 0.8 | **33.28** |
| PREMIUM | 10y | Labrador | 100 | 35 × 1.6 × 1.0 × 1.0 | **56.00** |

### 4.2 Eligible age

- We only quote pets aged **8 weeks to 14 years**, both ends inclusive. A pet is accepted on its 14th birthday and rejected from the next day.
- Age is measured from `birthDate` to today in the business time zone (4.0).
- The limits are configurable values, not hard-coded checks.
- A `birthDate` in the future means the pet is under 8 weeks, so it is rejected.

### 4.3 Validity

A quote is valid for **30 days** from creation. The duration is a single configurable value.

## 5. Errors

Every error returns a `ProblemDetail` body (`application/problem+json`) and never leaks stack traces.
Nothing is stored when a request fails.

| Status | When | Examples |
|--------|------|----------|
| 400 Bad Request | The request is malformed or the wrong shape | invalid JSON; wrong JSON type (`"deductible": "250"`); unknown or wrongly cased enum value (`"BIRD"`, `"dog"`); missing/null required field; date that doesn't parse (`"2023-13-40"`, `"10/05/2023"`); breed breaks the breed rules; `id` in the path isn't a UUID |
| 404 Not Found | Quote id is well-formed but doesn't exist | |
| 415 Unsupported Media Type | `POST` body isn't `application/json` | `Content-Type: text/plain` |
| 422 Unprocessable Entity | Well-formed input that breaks a business rule | country other than `US` (incl. `"us"`); deductible not in {100, 250, 500}; pet younger than 8 weeks or older than 14 years; future `birthDate` |
| 500 Internal Server Error | Unexpected failure | generic message only |

**Several errors in one request**
- **400:** every invalid field is reported in an `errors` array, e.g. `[{"field": "plan", "message": "must not be null"}, …]`.
- **422:** only the first broken rule is reported. Rules are checked in this order: country → deductible → age.

## 6. Constraints and limitations

- US only, USD only.
- **Authentication:** none today, but production will have it, so the design prepares for it:
  - Endpoints stay stateless.
  - Quotes store no customer identity.
  - Security can be added later as a Spring Security `SecurityFilterChain` in `config`, with no change to controllers or services. The README documents this.
  - No security code or dependency is added now.
- **Storage:** H2 **file** database (`./data/quotes`) through Spring Data JPA, so quotes **survive restarts**. Delete `./data` to reset.
  - Tests use an in-memory H2.
  - The schema is managed by Flyway migrations.
  - Switching to a real database (e.g. Postgres) must only need a driver and datasource config change, with no service changes.
- **H2 web console:** enabled only with the `local` Spring profile, off by default.
- No other features: no policy purchase, no quote updates or deletes, no listing.
- Tech and conventions: see `CLAUDE.md`.

## 7. Acceptance criteria → tests

### Create quote — happy path
| # | Criterion | Test level |
|---|-----------|-----------|
| AC1 | Valid request → 201, absolute `Location` ending in `/quotes/{id}`, body contains all fields from section 3 | Controller + E2E |
| AC2 | Each worked example in 4.1 gives the exact premium shown | Service (parameterized) |
| AC3 | `breed` omitted → no breed factor, quote created | Service + Controller |
| AC4 | High-risk breed matches case-insensitively and trimmed (`"  pUg "`); a partial name (`"Bulldog Mix"`) does not match | Service (parameterized) |
| AC5 | `validUntil` = `createdAt` + 30 days with a fixed `Clock` | Service |
| AC6 | Premium is rounded only at the end, HALF_UP, 2 decimals (`20.00`, not `20`) | Pricing unit test |
| AC30 | Breed `"  Pug "` is stored and returned as `"Pug"` | Service |
| AC31 | Unknown request field (`"color": "black"`) is ignored → 201 | Controller |

### Age boundaries (fixed `Clock`)
| # | Criterion | Test level |
|---|-----------|-----------|
| AC7 | Exactly 8 weeks old → accepted; one day younger → 422 | Service (parameterized) |
| AC8 | Exactly 14 years old (on the 14th birthday) → accepted; one day older → 422 | Service (parameterized) |
| AC9 | Future `birthDate` → 422 | Service |
| AC10 | Age factor boundaries: day before 1st birthday ×1.0, on 1st birthday ×1.2, day before 8th birthday ×1.2, on 8th birthday ×1.6 | Service (parameterized) |
| AC27 | "Today" uses the business zone: at `2026-11-20T03:00Z` a pet born `2025-11-20` is still under 1 year (×1.0) with `America/New_York`, but 1 year (×1.2) with zone `UTC` | Service |

### Get quote
| # | Criterion | Test level |
|---|-----------|-----------|
| AC11 | Existing id → 200 with the same body as on create | Controller + E2E |
| AC12 | Unknown UUID → 404 ProblemDetail | Controller + E2E |
| AC13 | Non-UUID id → 400 ProblemDetail | Controller |
| AC26 | `status` is `ACTIVE` right after creation and one second before `validUntil`, and `EXPIRED` at exactly `validUntil` and after | Service (fixed clocks) + Controller |

### Invalid input (nothing is stored)
| # | Criterion | Test level |
|---|-----------|-----------|
| AC14 | Malformed JSON / empty body → 400 | Controller |
| AC15 | Each required field missing or null → 400 | Controller (parameterized) |
| AC16 | Wrong types (`"deductible": "250"`, `"petType": 1`, `"birthDate": 20230510`) → 400 | Controller (parameterized) |
| AC17 | Unknown enum values (`petType: "BIRD"`, `plan: "GOLD"`, lowercase `"dog"`) → 400 | Controller (parameterized) |
| AC18 | Bad dates (`"2023-02-30"`, `"2023-13-01"`, `"10/05/2023"`, `""`) → 400 | Controller (parameterized) |
| AC19 | Breed: blank, 51+ chars, digits/symbols (`"Pug123"`, `"<script>"`), accented letters (`"Löwchen"`) → 400; exactly 50 chars, `"Shih-Tzu"`, `"Jack Russell"`, `"O'Malley"` → accepted | Controller (parameterized) |
| AC20 | Deductible not allowed (`0`, `-100`, `300`, `100.5`) → 422; `100.00` accepted, priced as 100 and returned as `100` | Service (parameterized) |
| AC21 | Country not supported (`"IL"`, `"us"`, `"USA"`) → 422 | Service (parameterized) |
| AC22 | After any rejected request nothing was stored | Service |
| AC23 | Error responses are `ProblemDetail` with no stack trace | Controller |
| AC28 | `Content-Type: text/plain` on POST → 415 ProblemDetail | Controller |
| AC29 | A 400 with several invalid fields lists all of them in `errors`; a 422 with several broken rules reports only the first (order: country → deductible → age) | Controller (400) + Service (422) |

### Persistence and concurrency
| # | Criterion | Test level |
|---|-----------|-----------|
| AC24 | Many concurrent creates → every quote gets a unique id and is retrievable | Repository (H2) |
| AC25 | Flyway creates the schema and Hibernate validation passes; a stored quote reads back unchanged: premium and deductible keep scale 2, enums are stored as strings, timestamps match | Repository (`@DataJpaTest`) |

## 8. Decisions (resolved open questions)

Answered on 2026-11-20. These are reflected in the sections above.

| # | Question | Decision |
|---|----------|----------|
| 1 | Is the pet accepted on its 14th birthday? | Yes, inclusive; rejected from the next day |
| 2 | Unknown JSON fields | Ignored |
| 3 | Is `country` case-sensitive? | Yes: `"us"` → 422 |
| 4 | GET of an expired quote | 200 with a computed `status` field: `ACTIVE` / `EXPIRED` |
| 5 | Unknown `petType` / `plan` values | 400 (as `CLAUDE.md` says) |
| 6 | Time zone for "today" | Configurable `quote.business-zone`, default `America/New_York`; timestamps stay UTC |
| 7 | Deductible `100.00` in the response | Normalized to `100` |
| 8 | Breed whitespace | Trimmed before storing and returning |
| 9 | `Location` header | Absolute URL |
| 10 | Wrong `Content-Type` | 415 ProblemDetail |
| 11 | Breed characters | English letters only, plus space, `-`, `'` |
| 12 | Several errors in one request | 400: list all invalid fields; 422: first broken rule |
| 13 | Schema management | Flyway migrations |
| 14 | H2 mode | File database (`./data`), survives restarts; tests use in-memory |
| 15 | H2 web console | Only with the `local` profile |
| 16 | Authentication preparation | Design only: stateless, no customer data, README explains where Spring Security plugs in; no code or dependency now |

## Log

- **2026-11-19** — Reorganized `SPEC.md` into overview, request/response contract, business rules, error mapping, constraints, numbered acceptance criteria mapped to tests, and open questions. Files: `SPEC.md` (documentation only). Tests: none run (no code changed).
- **2026-11-20** — Switched persistence from a `ConcurrentHashMap` repository to H2 in-memory through Spring Data JPA, behind the same `QuoteRepository` interface. Files: `CLAUDE.md` (persistence and testing rules), `SPEC.md` (§6 storage, AC24 test level, new AC25 for the mapping round trip), `PLAN.md` (slice 1 files, tests and dependencies; repository test level; DB open questions). Tests: none run (documentation only).
- **2026-11-20** — Resolved all 16 open questions (§8). Added the business time zone (§4.0), a computed `status` field, 415 handling, multi-error rules, an English-only breed rule, Flyway, the H2 file database with a `local`-profile console, and auth design notes; new AC26–AC31. Files: `SPEC.md` (contract, rules, errors, constraints, ACs, decisions), `PLAN.md` (slices updated with the new tests, files and dependencies; open questions replaced by decisions), `CLAUDE.md` (persistence, time zone and testing rules). Tests: none run (documentation only).