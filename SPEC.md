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

`POST /quotes`, body `application/json`:

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
| `petType` | string (enum) | Yes | `DOG`, `CAT` | Enum, easy to add new pet types |
| `breed` | string | No | Free text, see breed rules | Validate length and characters |
| `birthDate` | string, ISO date `yyyy-MM-dd` | Yes | A real calendar date; pet age must be in range (section 4) | |
| `plan` | string (enum) | Yes | `BASIC`, `PREMIUM` | Enum, easy to add new plans |
| `deductible` | JSON number (USD) | Yes | `100`, `250`, `500` | `BigDecimal`, never `double` |
| `country` | string | Yes | `US` only | Open for extension: country → currency mapping, add countries later without changing existing code |

**Breed rules** (when present):
- Trimmed length 1–50 characters.
- Only letters, spaces, hyphens (`-`) and apostrophes (`'`).

## 3. Response

`201 Created`, `Location: /quotes/{id}`. `GET /quotes/{id}` returns `200` with the same body.

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
  "validUntil": "2026-12-19T10:00:00Z"
}
```
- `id` is a server-generated UUID.
- `monthlyPremium` has exactly 2 decimals (e.g. `20.00`).
- `validUntil` = `createdAt` + 30 days. Time comes from the injected `Clock`.

## 4. Business rules
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

- We only quote pets aged **8 weeks to 14 years**, both ends inclusive. Age is measured from `birthDate` to today's date (from `Clock`).
- The limits are configurable values, not hard-coded checks.
- A `birthDate` in the future means the pet is under 8 weeks, so it is rejected.

### 4.3 Validity

A quote is valid for **30 days** from creation. The duration is a single configurable value.

## 5. Errors

Every error returns a `ProblemDetail` body and never leaks stack traces. Nothing is stored when a request fails.

| Status | When | Examples |
|--------|------|----------|
| 400 Bad Request | The request is malformed or the wrong shape | invalid JSON; wrong JSON type (`"deductible": "250"`); unknown enum value (`"petType": "BIRD"`); missing/null required field; date that doesn't parse (`"2023-13-40"`, `"10/05/2023"`); breed breaks the breed rules; `id` in the path isn't a UUID |
| 422 Unprocessable Entity | Well-formed input that breaks a business rule | pet younger than 8 weeks or older than 14 years; future `birthDate`; deductible not in {100, 250, 500}; country other than `US` |
| 404 Not Found | Quote id is well-formed but doesn't exist | |
| 500 Internal Server Error | Unexpected failure | generic message only |

## 6. Constraints and limitations

- US only, USD only.
- No authentication or authorization.
- In-memory, thread-safe storage. Swapping in a real database (e.g. JPA/Postgres) must only need a new repository implementation, with no service changes.
- No other features (no policy purchase, no quote updates or deletes, no listing).
- Tech and conventions: see `CLAUDE.md`.

## 7. Acceptance criteria → tests

### Create quote — happy path
| # | Criterion | Test level |
|---|-----------|-----------|
| AC1 | Valid request → 201, `Location: /quotes/{id}`, body contains all fields from section 3 | Controller + E2E |
| AC2 | Each worked example in 4.1 gives the exact premium shown | Service (parameterized) |
| AC3 | `breed` omitted → no breed factor, quote created | Service + Controller |
| AC4 | High-risk breed matches case-insensitively and trimmed (`"  pUg "`); a partial name (`"Bulldog Mix"`) does not match | Service (parameterized) |
| AC5 | `validUntil` = `createdAt` + 30 days with a fixed `Clock` | Service |
| AC6 | Premium is rounded only at the end, HALF_UP, 2 decimals (`20.00`, not `20`) | Pricing unit test |

### Age boundaries (fixed `Clock`)
| # | Criterion | Test level |
|---|-----------|-----------|
| AC7 | Exactly 8 weeks old → accepted; one day younger → 422 | Service (parameterized) |
| AC8 | Exactly 14 years old → accepted; one day older → 422 | Service (parameterized) |
| AC9 | Future `birthDate` → 422 | Service |
| AC10 | Age factor boundaries: day before 1st birthday ×1.0, on 1st birthday ×1.2, day before 8th birthday ×1.2, on 8th birthday ×1.6 | Service (parameterized) |

### Get quote
| # | Criterion | Test level |
|---|-----------|-----------|
| AC11 | Existing id → 200 with the same body as on create | Controller + E2E |
| AC12 | Unknown UUID → 404 ProblemDetail | Controller + E2E |
| AC13 | Non-UUID id → 400 ProblemDetail | Controller |

### Invalid input (nothing is stored)
| # | Criterion | Test level |
|---|-----------|-----------|
| AC14 | Malformed JSON / empty body → 400 | Controller |
| AC15 | Each required field missing or null → 400 | Controller (parameterized) |
| AC16 | Wrong types (`"deductible": "250"`, `"petType": 1`, `"birthDate": 20230510`) → 400 | Controller (parameterized) |
| AC17 | Unknown enum values (`petType: "BIRD"`, `plan: "GOLD"`, lowercase `"dog"`) → 400 | Controller (parameterized) |
| AC18 | Bad dates (`"2023-02-30"`, `"2023-13-01"`, `"10/05/2023"`, `""`) → 400 | Controller (parameterized) |
| AC19 | Breed: blank, 51+ chars, digits/symbols (`"Pug123"`, `"<script>"`) → 400; exactly 50 chars, `"Shih-Tzu"`, `"Jack Russell"` → accepted | Controller (parameterized) |
| AC20 | Deductible not allowed (`0`, `-100`, `300`, `100.5`) → 422; `100.00` treated as `100` | Service (parameterized) |
| AC21 | Country not supported (`"IL"`, `"us"` — see open question 3) → 422 | Service (parameterized) |
| AC22 | After any rejected request the repository is still empty | Service |
| AC23 | Error responses are `ProblemDetail` with no stack trace | Controller |

### Concurrency
| # | Criterion | Test level |
|---|-----------|-----------|
| AC24 | Many concurrent creates → every quote gets a unique id and is retrievable | Repository/Service |

## 8. Open questions

Defaults are in parentheses. Confirm or change them before implementing.

1. Upper age limit: is the pet accepted **on** its 14th birthday? (yes, inclusive; rejected from the next day)
2. Unknown JSON fields (e.g. `"color": "black"`): reject with 400 or ignore? (ignore)
3. Is `country` case-sensitive (`"us"`)? (yes, `"us"` → 422)
4. Getting an expired quote: still 200, or a different status? (200; the client compares `validUntil`)
5. Unknown `petType`/`plan` values → 400, following `CLAUDE.md`. The original brief could be read as 422 for "wrong input data". (400)

## Log

- **2026-11-19** — Reorganized `SPEC.md` into overview, request/response contract, business rules, error mapping, constraints, numbered acceptance criteria mapped to tests, and open questions. Files: `SPEC.md` (documentation only). Tests: none run (no code changed).
