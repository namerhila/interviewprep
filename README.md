# Pet Insurance Quotes

A small backend service that gives customers a monthly pet-insurance price quote.
Java 21 · Spring Boot 4 · Maven wrapper. Full requirements: [`SPEC.md`](SPEC.md).

## Run

```bash
./mvnw spring-boot:run            # http://localhost:8080
PORT=9090 ./mvnw spring-boot:run  # another port
```

Quotes are stored **in memory** and are lost when the app stops.

## Test

```bash
./mvnw test                               # all tests
./mvnw test -Dtest=PremiumCalculatorTest  # one class
```

| Level | Classes | What they cover |
|-------|---------|-----------------|
| Unit (no Spring) | `PremiumCalculatorTest`, `QuoteServiceTest`, `QuoteServiceBusinessRulesTest`, `InMemoryQuoteRepositoryTest` | pricing and rounding, age/deductible/country rules, status, concurrency |
| Controller (`@WebMvcTest`) | `QuoteControllerTest`, `QuoteRequestValidationTest` | HTTP status, headers and body; 400 validation matrix; error format |
| End to end (`@SpringBootTest`) | `QuoteApiIntegrationTest`, `QuoteApiErrorFlowsIntegrationTest` | main flows through the full app with a fixed clock |

## API

### Create a quote: `POST /quotes`

```bash
curl -i -X POST localhost:8080/quotes -H 'Content-Type: application/json' \
  -d '{"petType":"DOG","breed":"Pug","birthDate":"2023-05-10","plan":"PREMIUM","deductible":250,"country":"US"}'
```

```http
HTTP/1.1 201
Location: http://localhost:8080/quotes/614ac34e-3f11-4d6f-b585-b346b4468a58

{"id":"614ac34e-3f11-4d6f-b585-b346b4468a58","petType":"DOG","breed":"Pug","birthDate":"2023-05-10",
 "plan":"PREMIUM","deductible":250,"country":"US","monthlyPremium":49.14,"currency":"USD",
 "createdAt":"2026-11-20T13:02:53.977773Z","validUntil":"2026-12-20T13:02:53.977773Z","status":"ACTIVE"}
```

| Field | Required | Values |
|-------|----------|--------|
| `petType` | yes | `DOG`, `CAT` |
| `breed` | no | free text, 1–50 characters after trimming |
| `birthDate` | yes | ISO date `yyyy-MM-dd`; pet must be 8 weeks to 14 years old |
| `plan` | yes | `BASIC`, `PREMIUM` |
| `deductible` | yes | JSON number: `100`, `250` or `500` (USD) |
| `country` | yes | `US` (case-sensitive) |

### Get a quote: `GET /quotes/{id}`

```bash
curl -i localhost:8080/quotes/614ac34e-3f11-4d6f-b585-b346b4468a58
```

Returns `200` with the same body. `status` is computed when the quote is read: `ACTIVE` until `validUntil`, then `EXPIRED`.

### Pricing

`monthlyPremium = base × age factor × breed factor × deductible factor`, rounded once at the end (2 decimals, half-up).

| Factor | Rule |
|--------|------|
| Base | `BASIC` 20.00, `PREMIUM` 35.00 |
| Age (full years, UTC date) | under 1 ×1.0 · 1–7 ×1.2 · 8+ ×1.6 |
| High-risk breed | ×1.3 for French Bulldog, Bulldog, Pug, Persian (whole name, case-insensitive) |
| Deductible | 100 ×1.0 · 250 ×0.9 · 500 ×0.8 |

Example: PREMIUM, 3-year-old Pug, deductible 250 → 35 × 1.2 × 1.3 × 0.9 = **49.14**.

### Errors

Every error is an RFC 9457 `ProblemDetail` (`application/problem+json`) with no stack trace. A rejected request stores nothing.

| Status | When | Example `detail` |
|--------|------|------------------|
| 400 | malformed JSON, wrong JSON type (`"250"` for a number), unknown enum value, bad date, missing/null field, breed too long or blank, non-UUID id | `Invalid value for field 'deductible'` |
| 404 | unknown quote id | `Quote 3f1c… was not found` |
| 422 | valid input that breaks a business rule (checked in order: country → deductible → age) | `Pet must be at most 14 years old` |
| 500 | unexpected error | `An unexpected error occurred` |

```bash
# 400: every invalid field is listed
curl -s -X POST localhost:8080/quotes -H 'Content-Type: application/json' -d '{"petType":"DOG","country":"US"}'
```
```json
{"detail":"The request has invalid fields","instance":"/quotes","status":400,"title":"Invalid request",
 "errors":[{"field":"birthDate","message":"must not be null"},{"field":"deductible","message":"must not be null"},
           {"field":"plan","message":"must not be null"}]}
```

```bash
# 422: only the first broken rule is reported
curl -s -X POST localhost:8080/quotes -H 'Content-Type: application/json' \
  -d '{"petType":"CAT","birthDate":"2024-01-01","plan":"BASIC","deductible":300,"country":"US"}'
```
```json
{"detail":"Deductible 300 is not supported. Allowed: 100, 250, 500","instance":"/quotes","status":422,"title":"Business rule violation"}
```

## Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `PORT` (env) | `8080` | HTTP port |
| `quote.validity` | `30d` | how long a quote stays valid |
| `quote.min-pet-age` | `8w` | youngest pet quoted (inclusive) |
| `quote.max-pet-age` | `14y` | oldest pet quoted (inclusive) |

Pricing factors are in `config/PricingConfig`. The allowed deductibles are the keys of its deductible table.

## Design

- **Layers:** controller → service → repository interface. Request/response DTOs stay in the controller layer; the service works with domain records.
- **Money** is `BigDecimal` throughout; **time** comes only from the injected `Clock` (UTC).
- **Validation is split:** shape checks (Bean Validation + strict Jackson coercion, `config/JacksonConfig`) return 400; business rules (`service/QuoteValidator`) return 422.
- **Storage:** `InMemoryQuoteRepository` (`ConcurrentHashMap`, `putIfAbsent`) behind `QuoteRepository`. A database implementation can replace it without changing the service.
- **Extending:** a new pet type or plan is an enum constant (plus a base price); a new country is a `Country` constant with its currency.

## Authentication

Out of scope for now. The API is ready for it: endpoints are stateless and quotes hold no customer identity.
Security can be added as a Spring Security `SecurityFilterChain` bean in `config`, with no change to controllers or services.

## Next steps

From [`SPEC.md` §9](SPEC.md):
- a database (H2/Postgres via Spring Data JPA, Flyway migrations)
- a configurable business time zone for "today" instead of UTC
- 415 for a wrong `Content-Type`
- a character pattern for `breed` (letters, space, `-`, `'`)
