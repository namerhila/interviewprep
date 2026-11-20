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
- Character rules (which letters and symbols are allowed) are out of scope for now (§9).

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

### 4.0 "Today"

- Calendar-date rules (the pet's age) use **today's date in UTC**, from the injected `Clock`.
- Example: at `2026-11-20T03:00Z` "today" is `2026-11-20`, even though it is still the 19th in the US.
- A configurable business time zone is a next step (§9).

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
- Age is measured from `birthDate` to today in UTC (4.0).
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
- **Storage:** thread-safe in-memory repository (`ConcurrentHashMap`) behind the `QuoteRepository` interface. Quotes are lost on restart.
  - A database implementation can be added later without changing the service (§9).
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
| AC27 | "Today" is the UTC date: at `2026-11-20T03:00Z` a pet born `2025-11-20` is 1 year old (×1.2) | Service |

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
| AC19 | Breed: blank or longer than 50 characters after trimming → 400; exactly 50 characters (also with surrounding spaces) → accepted | Controller (parameterized) |
| AC20 | Deductible not allowed (`0`, `-100`, `300`, `100.5`) → 422; `100.00` accepted, priced as 100 and returned as `100` | Service (parameterized) |
| AC21 | Country not supported (`"IL"`, `"us"`, `"USA"`) → 422 | Service (parameterized) |
| AC22 | After any rejected request nothing was stored | Service |
| AC23 | Error responses are `ProblemDetail` with no stack trace | Controller |
| AC29 | A 400 with several invalid fields lists all of them in `errors`; a 422 with several broken rules reports only the first (order: country → deductible → age) | Controller (400) + Service (422) |

### Persistence and concurrency
| # | Criterion | Test level |
|---|-----------|-----------|
| AC24 | Many concurrent saves → every quote is stored and retrievable; saving a duplicate id is rejected and keeps the original | Repository |

AC25 (database mapping) and AC28 (415) moved to §9.

## 8. Decisions (resolved open questions)

Answered on 2026-11-20. These are reflected in the sections above.

| # | Question | Decision |
|---|----------|----------|
| 1 | Is the pet accepted on its 14th birthday? | Yes, inclusive; rejected from the next day |
| 2 | Unknown JSON fields | Ignored |
| 3 | Is `country` case-sensitive? | Yes: `"us"` → 422 |
| 4 | GET of an expired quote | 200 with a computed `status` field: `ACTIVE` / `EXPIRED` |
| 5 | Unknown `petType` / `plan` values | 400 (as `CLAUDE.md` says) |
| 6 | Time zone for "today" | ~~Configurable business zone~~ → **UTC** (scope change 2026-11-20; business zone is in §9) |
| 7 | Deductible `100.00` in the response | Normalized to `100` |
| 8 | Breed whitespace | Trimmed before storing and returning |
| 9 | `Location` header | Absolute URL |
| 10 | Wrong `Content-Type` | ~~415 ProblemDetail~~ → moved to §9 (scope change) |
| 11 | Breed characters | ~~English letters only~~ → moved to §9 (scope change); only length is validated |
| 12 | Several errors in one request | 400: list all invalid fields; 422: first broken rule |
| 13 | Schema management | ~~Flyway~~ → moved to §9 (no database for now) |
| 14 | H2 mode | ~~File database~~ → in-memory `ConcurrentHashMap` (scope change) |
| 15 | H2 web console | ~~`local` profile~~ → moved to §9 (no database for now) |
| 16 | Authentication preparation | Design only: stateless, no customer data, README explains where Spring Security plugs in; no code or dependency now |

## 9. Out of scope / next steps

Dropped from the current time-boxed scope on 2026-11-20. Each item is ready to pick up later.

| Item | What it would add | Notes |
|------|-------------------|-------|
| **Database** | H2 (then Postgres) through Spring Data JPA, Flyway migrations, H2 console under a `local` profile | Add a JPA implementation of `QuoteRepository`; the service doesn't change. Previously planned AC25: the mapping round trip (money scale 2, enums as strings, timestamps) |
| **Business time zone** | `quote.business-zone` (e.g. `America/New_York`) used for "today" instead of UTC | Fixes the evening edge case where a US pet's age is one day ahead |
| **415 Unsupported Media Type** | A wrong `Content-Type` returns 415 `ProblemDetail`, with a test | Previously planned AC28 |
| **Breed character pattern** | Only `A–Z a–z`, space, `-`, `'`; reject digits, symbols, accented letters (`"Pug123"`, `"<script>"`, `"Löwchen"`) → 400 | Today only length (1–50 after trimming) is validated |

## Log

- **2026-11-19** — Reorganized `SPEC.md` into overview, request/response contract, business rules, error mapping, constraints, numbered acceptance criteria mapped to tests, and open questions. Files: `SPEC.md` (documentation only). Tests: none run (no code changed).
- **2026-11-20** — Switched persistence from a `ConcurrentHashMap` repository to H2 in-memory through Spring Data JPA, behind the same `QuoteRepository` interface. Files: `CLAUDE.md` (persistence and testing rules), `SPEC.md` (§6 storage, AC24 test level, new AC25 for the mapping round trip), `PLAN.md` (slice 1 files, tests and dependencies; repository test level; DB open questions). Tests: none run (documentation only).
- **2026-11-20** — Resolved all 16 open questions (§8). Added the business time zone (§4.0), a computed `status` field, 415 handling, multi-error rules, an English-only breed rule, Flyway, the H2 file database with a `local`-profile console, and auth design notes; new AC26–AC31. Files: `SPEC.md` (contract, rules, errors, constraints, ACs, decisions), `PLAN.md` (slices updated with the new tests, files and dependencies; open questions replaced by decisions), `CLAUDE.md` (persistence, time zone and testing rules). Tests: none run (documentation only).
- **2026-11-20** — Scope change (time-boxed): dropped H2/JPA/Flyway, switched "today" to UTC, and moved the database, business time zone, 415 and breed character pattern to the new §9 "Out of scope / next steps" (AC25 and AC28 moved there; AC19, AC24 and AC27 reworded). Files: `SPEC.md`, `CLAUDE.md` (persistence back to in-memory, UTC date rule, repository test rule), `PLAN.md` (scope note, slices 2–5 and AC table updated). Tests: no code changed in this step.
- **2026-11-20** — **Slice 1 implemented**: `POST /quotes` and `GET /quotes/{id}` end to end, premium = plan base price, computed `status`, absolute `Location`, 404 and generic 500 as `ProblemDetail`. Covers AC1, AC5, AC11, AC12, AC24, AC26, AC31 (AC2 partly). Files (main, all new except the last two): `domain/` `PetType`, `Plan`, `QuoteStatus`, `Quote`, `QuoteView`, `QuoteRequest`; `repository/` `QuoteRepository`, `InMemoryQuoteRepository` (`ConcurrentHashMap`, `putIfAbsent`); `service/QuoteService`; `config/` `ClockConfig`, `QuoteProperties`; `dto/` `CreateQuoteRequest`, `QuoteResponse`; `controller/QuoteController`; `error/` `QuoteNotFoundException`, `GlobalExceptionHandler`; `InterviewprepApplication` (`@ConfigurationPropertiesScan`); `application.properties` (port, validity). Tests (new): `QuoteServiceTest` (12), `InMemoryQuoteRepositoryTest` (4), `QuoteControllerTest` (5), `QuoteApiIntegrationTest` (2); existing `InterviewprepApplicationTests` unchanged. `./mvnw test`: **24 run, 0 failures, 0 errors, 0 skipped**. No dependencies added.
- **2026-11-20** — **Slice 2 implemented**: full pricing (base × age × high-risk breed × deductible, rounded once HALF_UP to 2 decimals), "today" = UTC date, breed stored trimmed. Covers AC2, AC3, AC4, AC6, AC10, AC27, AC30. Files: new `domain/AgeBand`, `domain/PricingRules` (all factors and their lookups in one place; deductible matched by value so `100.00` = `100`), `config/PricingConfig` (SPEC values), `service/PremiumCalculator`; changed `domain/QuoteRequest` (strips breed), `service/QuoteService` (uses the calculator with the UTC date; temporary base-price switch removed). Tests: new `PremiumCalculatorTest` (30: worked examples, breed matching, age boundaries, deductible by value, scale, rounding with custom rules); `QuoteServiceTest` +4 (worked example, no breed, trimmed breed, UTC today) and its `serviceAt` helper now passes a `PremiumCalculator` (setup only, no assertion changed); `QuoteControllerTest` +1 (no breed). `./mvnw test`: **59 run, 0 failures, 0 errors, 0 skipped**. Known gap until slice 3: an unsupported deductible (e.g. 300) or a birthDate more than a year in the future returns 500 instead of 422.
- **2026-11-20** — **Slice 3 implemented**: business-rule rejections return 422 `ProblemDetail` (title "Business rule violation"), checked in order country → deductible → age, first broken rule reported, nothing stored. Covers AC7, AC8, AC9, AC20, AC21, AC22 (service side), AC29 (422 part); closes the slice 2 gap (unsupported deductible / far-future birthDate no longer 500). Files: new `domain/Country` (`US`→`USD`, exact-case lookup), `error/BusinessRuleException`, `service/QuoteValidator` (rules + readable messages, returns the request with the deductible normalized to its allowed value); changed `config/QuoteProperties` (`minPetAge` 8w, `maxPetAge` 14y, configurable), `domain/PricingRules` (`supportedDeductible`, `allowedDeductibles`: the allowed values are the factor-table keys), `service/QuoteService` (validates before pricing/saving; country and currency from `Country`), `error/GlobalExceptionHandler` (422), `application.properties` (age limits). Tests: new `QuoteServiceBusinessRulesTest` (21: age boundaries, future dates, deductibles incl. `100.00` → `100`, countries incl. `"us"`, rule order, configurable limits; a save-counting wrapper around the real repository proves nothing is stored); `QuoteControllerTest` +1 (422 mapping); `QuoteServiceTest` setup only: `PROPERTIES` gets the age limits and `serviceAt` passes a `QuoteValidator` (no assertion changed). `./mvnw test`: **81 run, 0 failures, 0 errors, 0 skipped**. Known gap until slice 4: null `deductible`/`birthDate` still return 500 (Bean Validation comes in slice 4).
