# Plan — Pet Insurance Quotes

Implementation plan for `SPEC.md`, following `CLAUDE.md`. There are five vertical slices.
Each slice is implemented, reviewed, tested and committed on its own.

> **Scope change (2026-11-20, time-boxed):** no database (H2/JPA/Flyway dropped), a `ConcurrentHashMap`
> repository in main code, UTC for "today". The business time zone, 415 and the breed character pattern
> moved to SPEC.md §9 "Out of scope / next steps".

Paths are relative to `src/main/java/com/hila/interviewprep/` (main) and
`src/test/java/com/hila/interviewprep/` (test).

**Every slice follows the same loop:** write the failing tests first, write the minimal code to pass them,
run `./mvnw test` and report pass/fail counts, then append a `## Log` entry to `SPEC.md`.

**Test levels**
- **Unit**: plain JUnit 5 + AssertJ, fixed `Clock`, real `InMemoryQuoteRepository`, no Spring, no mocks.
- **WebMvc**: `@WebMvcTest(QuoteController.class)` with `QuoteService` mocked. Assert status, headers and body.
- **E2E**: `@SpringBootTest` + MockMvc, main flows only.

All design decisions come from SPEC.md §8.

---

## Slice 1 — Create and get a quote (happy path, end to end) ✅ done

Goal: `POST /quotes` stores a quote in the in-memory repository and `GET /quotes/{id}` returns it with its `status`.
For now the premium is just the plan's base price. Slice 2 adds the factors.

**Acceptance criteria:** AC1, AC5, AC11, AC12, AC24, AC26, AC31 (AC2 only partly: base price)

**No new dependencies.**

**Tests**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/QuoteServiceTest` | quote has a UUID and the request details, currency USD; `createdAt` = clock instant, `validUntil` = +30 days (AC5); premium BASIC 20.00 / PREMIUM 35.00 with scale 2; new quote `ACTIVE`; created quote fetched by id (AC11); unknown id → `QuoteNotFoundException` (AC12); same request twice → two distinct quotes; `status` read through a second service with a later fixed clock: `validUntil` − 1s `ACTIVE`, exactly `validUntil` and later `EXPIRED` (AC26) |
| Unit | `repository/InMemoryQuoteRepositoryTest` | save + findById; unknown id → empty; duplicate id rejected, original kept; 50 concurrent saves all stored (AC24) |
| WebMvc | `controller/QuoteControllerTest` | POST → 201, absolute `Location` `http://localhost/quotes/{id}`, full body incl. `status` and `"monthlyPremium":35.00` (AC1, AC26); extra field `color` ignored (AC31); GET → 200 (AC11); unknown → 404 ProblemDetail (AC12); unexpected exception → generic 500, no internals |
| E2E | `QuoteApiIntegrationTest` | POST then GET the `Location` → same body, `ACTIVE` (AC1, AC11, AC26); random UUID → 404 (AC12) |

**Files**
| Action | File | Why |
|--------|------|-----|
| create | `domain/PetType.java`, `domain/Plan.java`, `domain/QuoteStatus.java` | enums |
| create | `domain/Quote.java` | record + `statusAt(Instant)` (ACTIVE while now < `validUntil`) |
| create | `domain/QuoteView.java` | quote + status, which is what the service returns, so the controller never needs the `Clock` |
| create | `domain/QuoteRequest.java` | the domain input of the service |
| create | `repository/QuoteRepository.java` | interface `save`, `findById` (a DB implementation can be added later) |
| create | `repository/InMemoryQuoteRepository.java` | `ConcurrentHashMap`, `putIfAbsent` (duplicate id → `IllegalStateException`) |
| create | `service/QuoteService.java` | create + get; base price per plan (temporary, replaced in slice 2) |
| create | `config/ClockConfig.java` | `Clock.systemUTC()` bean |
| create | `config/QuoteProperties.java` | `quote.validity` (default 30d) |
| create | `dto/CreateQuoteRequest.java`, `dto/QuoteResponse.java` | request/response records + mapping |
| create | `controller/QuoteController.java` | POST / GET, absolute `Location` via `ServletUriComponentsBuilder` |
| create | `error/QuoteNotFoundException.java` | → 404 |
| create | `error/GlobalExceptionHandler.java` | extends `ResponseEntityExceptionHandler`; 404 + generic 500 |
| change | `InterviewprepApplication.java` | `@ConfigurationPropertiesScan` |
| change | `application.properties` | `server.port=${PORT:8080}`, `quote.validity=30d` |

---

## Slice 2 — Pricing factors

Goal: the premium follows SPEC §4.1 exactly, with "today" as the UTC date (§4.0).

**Acceptance criteria:** AC2, AC3, AC4, AC6, AC10, AC27, AC30

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/PremiumCalculatorTest` | `@ParameterizedTest` over the four worked examples (AC2); no breed → no breed factor (AC3); `"Pug"`, `"  pUg "`, `"FRENCH BULLDOG"`, `"persian"` → ×1.3, but `"Bulldog Mix"` and `"Labrador"` → ×1.0 (AC4); age factor at the day before/on the 1st birthday and the day before/on the 8th birthday (AC10); rounding: custom `PricingRules` whose product has 3+ decimals (e.g. base 10.00 × 1.005 → 10.05, × 1.0049 → 10.05, × 1.0044 → 10.04) to prove HALF_UP happens once at the end; result always has scale 2 (AC6) |
| Unit | `service/QuoteServiceTest` (add) | PREMIUM / 3y / Pug / 250 → 49.14 through the service (AC2); breed null → no breed factor (AC3); `"  Pug "` stored and returned as `"Pug"` (AC30); clock fixed at `2026-11-20T03:00Z`, pet born `2025-11-20` → 1 year, ×1.2 (AC27) |

**Files**
| Action | File | Why |
|--------|------|-----|
| create | `domain/PricingRules.java` | record: base price per plan, age bands, high-risk breed set, deductible factors. All factors live here in one place |
| create | `service/PremiumCalculator.java` | pure function `(QuoteRequest, LocalDate today) → BigDecimal`; the age in full years comes from `Period.between` |
| create | `config/PricingConfig.java` | `PricingRules` bean with the SPEC values (BigDecimal built from strings) |
| change | `domain/QuoteRequest.java` | compact constructor strips `breed` (AC30) |
| change | `service/QuoteService.java` | `today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)`; delegate to `PremiumCalculator`; remove the temporary base-price switch |

> ⚠️ The slice 1 base-price tests use a puppy with no breed and deductible 100, so they stay valid
> once the factors are added. No existing test has to change.

---

## Slice 3 — Business-rule rejections (422)

Goal: well-formed requests that break a rule return 422 (first broken rule only), and nothing is stored.

**Acceptance criteria:** AC7, AC8, AC9, AC20, AC21, AC22 (service side), AC29 (422 part)

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/QuoteServiceTest` (add) | `@ParameterizedTest`: exactly 56 days old accepted / 55 days → `BusinessRuleException` (AC7); on the 14th birthday accepted / the day after rejected (AC8); birthDate tomorrow (UTC) rejected (AC9); deductible `0`, `-100`, `300`, `100.5` rejected, `100.00` accepted, priced as 100 and stored/returned as `100` (AC20); country `"IL"`, `"us"`, `"USA"` rejected, `"US"` accepted (AC21); after each rejection nothing was stored (AC22) |
| Unit | `service/QuoteServiceTest` (add) | rule order (AC29): bad country + bad deductible + too old → the message is about country; bad deductible + too old → about deductible |
| Unit | `service/QuoteServiceTest` (add) | age limits come from `QuoteProperties`: a custom min/max changes the outcome (configurable, SPEC §4.2) |
| WebMvc | `controller/QuoteControllerTest` (add) | service throws `BusinessRuleException` → 422 `ProblemDetail` with the message in `detail` |

**Files**
| Action | File | Why |
|--------|------|-----|
| create | `domain/Country.java` | enum `US("USD")` that maps country to currency; looked up from the string with an **exact-case** match |
| create | `error/BusinessRuleException.java` | → 422 |
| create | `service/QuoteValidator.java` | checks in order: country → deductible → age; throws on the first failure (or private methods in the service if small) |
| change | `config/QuoteProperties.java` | `minPetAge` (56 days), `maxPetAge` (P14Y) |
| change | `service/QuoteService.java` | run the checks before pricing and saving; currency from `Country`; store the matching allowed deductible (`100`), not the raw input (`100.00`) |
| change | `error/GlobalExceptionHandler.java` | map `BusinessRuleException` → 422 |
| change | `application.properties` | age limits |

> `country` is already a **string** in the request and the domain (since slice 1), so `"IL"` gets 422, not 400.
> The allowed deductibles are the keys of the deductible-factor table in `PricingRules`, so they're
> defined once and a 4th deductible is a config change only.

---

## Slice 4 — Input validation and strict JSON (400)

Goal: malformed or wrongly shaped requests return 400 (listing every invalid field), and the service is never called.

**Acceptance criteria:** AC13, AC14, AC15, AC16, AC17, AC18, AC19, AC22 (controller side), AC23, AC29 (400 part)

**Tests first** (all `@WebMvcTest`, mostly `@ParameterizedTest`)
| Test | Cases |
|------|-------|
| malformed JSON (AC14) | `{`, `not json`, empty body |
| missing / null required field (AC15) | each of petType, birthDate, plan, deductible, country, both missing and `null` |
| wrong types (AC16) | `"deductible": "250"`, `"petType": 1`, `"plan": true`, `"birthDate": 20230510`, `"country": 1` |
| unknown enums (AC17) | `petType: "BIRD"`, `plan: "GOLD"`, `petType: "dog"` |
| bad dates (AC18) | `"2023-02-30"`, `"2023-13-01"`, `"10/05/2023"`, `""` |
| breed length (AC19) | rejected: `""`, `"   "`, 51 characters; accepted: 50 characters, 50 characters with surrounding spaces |
| several invalid fields (AC29) | `plan` null + `breed` 51 characters → 400, and the `errors` array has both fields |
| path id (AC13) | `GET /quotes/not-a-uuid` → 400 |
| unknown fields still ignored (AC31) | re-run the extra-field case after the strict-JSON config, which must not turn on "fail on unknown properties" |
| no side effects (AC22) | for every 400 case, `verifyNoInteractions(quoteService)` |
| error body (AC23) | 400/404/422/500 responses are `application/problem+json`, have `status`/`title`/`detail`, and no `trace`/`exception` field |

**Files**
| Action | File | Why |
|--------|------|-----|
| change | `dto/CreateQuoteRequest.java` | `@NotNull` on required fields; the compact constructor strips `breed`, then `@Size(min = 1, max = 50)` checks the trimmed value (null is allowed, blank becomes `""` and fails) |
| change | `controller/QuoteController.java` | `@Valid` on the body |
| change | `error/GlobalExceptionHandler.java` | `MethodArgumentNotValidException` → 400 with an `errors` array of `{field, message}`; `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` → 400 |
| change | `application.properties` (or `config/JacksonConfig.java`) | strict Jackson: no scalar coercion (`"250"` → number), no numbers for enums, no numeric dates; unknown properties stay ignored |

> Risk: Jackson coerces by default (`"250"` → 250, `1` → enum ordinal, an integer → `LocalDate`).
> Spring Boot 4 ships Jackson 3.1, so the exact property or customizer names have to be checked during the slice.
> The WebMvc tests prove the config works because `@WebMvcTest` loads the Jackson auto-configuration.

---

## Slice 5 — End-to-end error flows and run instructions

Goal: prove that the full application wires validation, rules and errors together,
and make it easy for the reviewers to run the curl requests.

**Acceptance criteria:** confirms AC2, AC7/AC20, AC14, AC22 and AC23 end to end (no new behaviour)

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| E2E | `QuoteApiIntegrationTest` (add) | malformed JSON → 400; pet too old → 422; after these, a valid POST still works; worked example PREMIUM/3y/Pug/250 → 49.14 through HTTP with a fixed `Clock` bean (`@TestConfiguration`) |

**Files**
| Action | File | Why |
|--------|------|-----|
| change | `src/test/.../QuoteApiIntegrationTest.java` | the flows above |
| create | `README.md` | how to run (`./mvnw spring-boot:run`, `PORT`); data is in memory and lost on restart; curl examples for 201/200/400/404/422; **Authentication** section: out of scope, endpoints are stateless and quotes hold no customer identity, Spring Security plugs in as a `SecurityFilterChain` in `config` with no controller or service changes; **Next steps** from SPEC §9 |

---

## AC coverage

Every acceptance criterion from SPEC.md §7 and the slice(s) whose tests cover it.

| AC | Criterion (short) | Slice(s) |
|----|-------------------|----------|
| 1 | 201 + absolute `Location` + full body | 1 ✅ |
| 2 | Worked-example premiums | 1 ✅ (base price), 2, 5 |
| 3 | No breed → no breed factor | 2 |
| 4 | High-risk breed matching | 2 |
| 5 | `validUntil` = `createdAt` + 30 days | 1 ✅ |
| 6 | Round once, HALF_UP, scale 2 | 2 |
| 7 | 8-week minimum boundary | 3, 5 |
| 8 | 14-year maximum boundary | 3 |
| 9 | Future birthDate → 422 | 3 |
| 10 | Age-factor boundaries | 2 |
| 11 | GET existing → 200 | 1 ✅ |
| 12 | GET unknown → 404 | 1 ✅ |
| 13 | Non-UUID id → 400 | 4 |
| 14 | Malformed JSON → 400 | 4, 5 |
| 15 | Missing/null fields → 400 | 4 |
| 16 | Wrong types → 400 | 4 |
| 17 | Unknown enums → 400 | 4 |
| 18 | Bad dates → 400 | 4 |
| 19 | Breed length | 4 |
| 20 | Deductible not allowed → 422; `100.00` → `100` | 3, 5 |
| 21 | Country not supported (incl. `"us"`) → 422 | 3 |
| 22 | Nothing stored on rejection | 3, 4, 5 |
| 23 | ProblemDetail, no stack trace | 1 ✅ (500), 4, 5 |
| 24 | Concurrent saves + duplicate id | 1 ✅ |
| 25 | ~~DB mapping round trip~~ | out of scope (SPEC §9) |
| 26 | `status` ACTIVE / EXPIRED | 1 ✅ |
| 27 | "Today" is the UTC date | 2 |
| 28 | ~~Wrong Content-Type → 415~~ | out of scope (SPEC §9) |
| 29 | 400 lists all fields; 422 first rule | 3 (422), 4 (400) |
| 30 | Breed trimmed | 2 |
| 31 | Unknown fields ignored | 1 ✅, 4 |

## Risks to check during implementation

1. **Jackson 3 defaults**: coercion settings for slice 4. Also, unknown properties must stay ignored (AC31 guards this).
