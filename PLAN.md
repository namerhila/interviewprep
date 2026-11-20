# Plan — Pet Insurance Quotes

Implementation plan for `SPEC.md`, following `CLAUDE.md`. There are five vertical slices.
Each slice is implemented, reviewed, tested and committed on its own.

Paths are relative to `src/main/java/com/hila/interviewprep/` (main) and
`src/test/java/com/hila/interviewprep/` (test).

**Every slice follows the same loop:** write the failing tests first, write the minimal code to pass them,
run `./mvnw test` and report pass/fail counts, then append a `## Log` entry to `SPEC.md`.

**Test levels**
- **Unit**: plain JUnit 5 + AssertJ, fixed `Clock`. Service tests use a test-only `InMemoryQuoteRepository` fake (no Spring, no mocks).
- **Repository**: `@DataJpaTest` against in-memory H2 with the Flyway migrations applied, and `JpaQuoteRepository` imported.
- **WebMvc**: `@WebMvcTest(QuoteController.class)` with `QuoteService` mocked. Assert status, headers and body.
- **E2E**: `@SpringBootTest` + MockMvc, main flows only.

All design decisions come from SPEC.md §8.

---

## Slice 1 — Create and get a quote (happy path, end to end)

Goal: `POST /quotes` stores a quote in H2 and `GET /quotes/{id}` returns it with its `status`.
For now the premium is just the plan's base price. Slice 2 adds the factors.

**Acceptance criteria:** AC1, AC5, AC11, AC12, AC24, AC25, AC26, AC31 (AC2 only partly: base price)

**Dependencies to add to `pom.xml`** (approved; exact Boot 4 artifact names verified at the start of the slice)
| Dependency | Scope | Why |
|------------|-------|-----|
| `org.springframework.boot:spring-boot-starter-data-jpa` | compile | Spring Data JPA + Hibernate |
| `org.springframework.boot:spring-boot-starter-flyway` | compile | Flyway migrations (brings `flyway-core`, which supports H2) |
| `com.h2database:h2` | runtime | the database |
| `org.springframework.boot:spring-boot-starter-data-jpa-test` | test | `@DataJpaTest` (Boot 4 split the test slices into their own starters) |

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/QuoteServiceTest` | create returns a quote with a UUID, `createdAt` = clock instant, `validUntil` = `createdAt` + 30 days (AC5); `monthlyPremium` = BASIC 20.00 / PREMIUM 35.00 with scale 2; currency USD; created quote can be found by id (AC11); unknown id throws `QuoteNotFoundException` (AC12) |
| Unit | `service/QuoteServiceTest` | `status` (AC26): create with clock A, then read through a second service instance that shares the same fake repository and uses a later fixed clock: right after creation → `ACTIVE`; `validUntil` − 1s → `ACTIVE`; exactly `validUntil` → `EXPIRED`; `validUntil` + 1 day → `EXPIRED` |
| Repository | `repository/JpaQuoteRepositoryTest` | Flyway ran and Hibernate `validate` passed (the context starts) (AC25); save then findById returns an equal `Quote`: premium `20.00` keeps scale 2, deductible, enums, breed null and non-null, `createdAt`/`validUntil` (AC25); enum columns hold `"DOG"`/`"PREMIUM"` strings (native query) (AC25); findById for unknown id → empty (AC12) |
| Repository | `repository/JpaQuoteRepositoryConcurrencyTest` | 50 threads save concurrently → 50 distinct ids, all findable (AC24). Runs **without** the per-test transaction (`@Transactional(propagation = NOT_SUPPORTED)`) so the threads really commit, and cleans up afterwards |
| WebMvc | `controller/QuoteControllerTest` | POST valid body → 201, `Location` is absolute and ends with `/quotes/{id}` (MockMvc host: `http://localhost/quotes/{id}`), JSON body has every field from SPEC §3 including `status` (AC1, AC26); GET existing → 200 with the same body (AC11); GET unknown UUID → 404 `ProblemDetail` (AC12); body with an extra `"color": "black"` field → 201 (AC31) |
| E2E | `QuoteApiIntegrationTest` | POST then GET the `Location` → same body, `status` `ACTIVE` (AC1, AC11, AC26); GET random UUID → 404 (AC12) |

**Files**
| Action | File | Why |
|--------|------|-----|
| change | `pom.xml` | the four dependencies above; Surefire `systemPropertyVariables`: `spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`, so **every** test (including the existing, unchanged `InterviewprepApplicationTests`) uses in-memory H2 and never creates `./data` |
| change | `.gitignore` | add `data/` |
| create | `domain/PetType.java` | enum `DOG`, `CAT` |
| create | `domain/Plan.java` | enum `BASIC`, `PREMIUM` |
| create | `domain/QuoteStatus.java` | enum `ACTIVE`, `EXPIRED` |
| create | `domain/Quote.java` | record: id, pet details, plan, deductible, country, premium, currency, createdAt, validUntil; `statusAt(Instant now)` (ACTIVE while `now` < `validUntil`) |
| create | `domain/QuoteView.java` | record `(Quote quote, QuoteStatus status)`, which is what the service returns, so the controller never needs the `Clock` |
| create | `domain/QuoteRequest.java` | the domain input the service receives (the controller maps the DTO to it) |
| create | `repository/QuoteRepository.java` | interface `save(Quote)`, `findById(UUID)` using domain types only. This is all the service sees |
| create | `repository/QuoteEntity.java` | JPA `@Entity` (table `quotes`), `@Enumerated(STRING)` enums, `BigDecimal` money, `Instant` timestamps, implements `Persistable<UUID>`; mapping to and from `Quote`. Never leaves `repository` |
| create | `repository/QuoteJpaRepository.java` | package-private Spring Data `JpaRepository<QuoteEntity, UUID>` |
| create | `repository/JpaQuoteRepository.java` | `@Repository` adapter implementing `QuoteRepository` on top of `QuoteJpaRepository` |
| create | `src/main/resources/db/migration/V1__create_quotes.sql` | `quotes` table: `id UUID PK`, `pet_type VARCHAR(20)`, `breed VARCHAR(50) NULL`, `birth_date DATE`, `plan VARCHAR(20)`, `deductible DECIMAL(10,2)`, `country VARCHAR(2)`, `monthly_premium DECIMAL(10,2)`, `currency VARCHAR(3)`, `created_at TIMESTAMP WITH TIME ZONE`, `valid_until TIMESTAMP WITH TIME ZONE`, all `NOT NULL` except `breed`. No `status` column, since it's computed |
| create | `src/test/.../repository/InMemoryQuoteRepository.java` | test-only `ConcurrentHashMap` fake of `QuoteRepository` for the service unit tests |
| create | `service/QuoteService.java` | create + get, returning `QuoteView`; uses `Clock` and `QuoteProperties` |
| create | `config/ClockConfig.java` | `Clock` bean (`Clock.systemUTC()`) |
| create | `config/QuoteProperties.java` | `@ConfigurationProperties("quote")` record: `validity` (30d). Slice 2 adds `businessZone`, slice 3 the age limits |
| create | `dto/CreateQuoteRequest.java` | request record (no Bean Validation yet, added in slice 4) |
| create | `dto/QuoteResponse.java` | response record + mapping from `QuoteView` |
| create | `controller/QuoteController.java` | POST / GET; builds an absolute `Location` with `ServletUriComponentsBuilder` |
| create | `error/QuoteNotFoundException.java` | → 404 |
| create | `error/GlobalExceptionHandler.java` | `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` (Spring's built-in errors become `ProblemDetail`), plus 404 and a generic 500 |
| change | `InterviewprepApplication.java` | `@ConfigurationPropertiesScan` |
| change | `src/main/resources/application.properties` | `server.port=${PORT:8080}`; `quote.validity=30d`; `spring.datasource.url=jdbc:h2:file:./data/quotes`; `spring.jpa.hibernate.ddl-auto=validate`; `spring.jpa.open-in-view=false`; `spring.h2.console.enabled=false` |
| create | `src/main/resources/application-local.properties` | `spring.h2.console.enabled=true` (H2 console only with `--spring.profiles.active=local`) |

> **Timestamp precision:** H2 stores timestamps to the microsecond, but the system `Clock` can return nanoseconds.
> The service truncates `createdAt` to microseconds before saving, so the POST response and a later GET match exactly.
>
> **IDs:** the service generates the UUID (as `CLAUDE.md` requires), not the database. The entity has no `@GeneratedValue`.
> It implements `Persistable`/`isNew`, so Spring Data doesn't run a SELECT before every INSERT.
>
> **Status:** computed in the service from the `Clock` on every create and get, never stored.

---

## Slice 2 — Pricing factors and the business time zone

Goal: the premium follows SPEC §4.1 exactly, with "today" in the business zone (§4.0).

**Acceptance criteria:** AC2, AC3, AC4, AC6, AC10, AC27, AC30

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/PremiumCalculatorTest` | `@ParameterizedTest` over the four worked examples (AC2); no breed → no breed factor (AC3); `"Pug"`, `"  pUg "`, `"FRENCH BULLDOG"`, `"persian"` → ×1.3, but `"Bulldog Mix"` and `"Labrador"` → ×1.0 (AC4); age factor at the day before/on the 1st birthday and the day before/on the 8th birthday (AC10); rounding: custom `PricingRules` whose product has 3+ decimals (e.g. base 10.00 × 1.005 → 10.05, × 1.0049 → 10.05, × 1.0044 → 10.04) to prove HALF_UP happens once at the end; result always has scale 2 (AC6) |
| Unit | `service/QuoteServiceTest` (add) | PREMIUM / 3y / Pug / 250 → 49.14 through the service (AC2); breed null → no breed factor (AC3); `"  Pug "` stored and returned as `"Pug"` (AC30); business zone (AC27): clock fixed at `2026-11-20T03:00Z`, pet born `2025-11-20` → ×1.0 with `America/New_York`, ×1.2 with `UTC` |

**Files**
| Action | File | Why |
|--------|------|-----|
| create | `domain/PricingRules.java` | record: base price per plan, age bands, high-risk breed set, deductible factors. All factors live here in one place |
| create | `service/PremiumCalculator.java` | pure function `(QuoteRequest, LocalDate today) → BigDecimal`; the age in full years comes from `Period.between` |
| create | `config/PricingConfig.java` | `PricingRules` bean with the SPEC values (BigDecimal built from strings) |
| change | `config/QuoteProperties.java` | `businessZone` (`ZoneId`, default `America/New_York`) |
| change | `domain/QuoteRequest.java` | compact constructor strips `breed` (AC30) |
| change | `service/QuoteService.java` | `today = LocalDate.ofInstant(clock.instant(), businessZone)`; delegate to `PremiumCalculator` |
| change | `application.properties` | `quote.business-zone=America/New_York` |

> ⚠️ The slice 1 base-price assertions use a puppy with no breed and deductible 100, so they stay valid
> once the factors are added. Slice 1 tests must be written that way so no existing test has to change.

---

## Slice 3 — Business-rule rejections (422)

Goal: well-formed requests that break a rule return 422 (first broken rule only), and nothing is stored.

**Acceptance criteria:** AC7, AC8, AC9, AC20, AC21, AC22 (service side), AC29 (422 part)

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| Unit | `service/QuoteServiceTest` (add) | `@ParameterizedTest`: exactly 56 days old accepted / 55 days → `BusinessRuleException` (AC7); on the 14th birthday accepted / the day after rejected (AC8); birthDate tomorrow (business zone) rejected (AC9); deductible `0`, `-100`, `300`, `100.5` rejected, `100.00` accepted, priced as 100 and stored/returned as `100` (AC20); country `"IL"`, `"us"`, `"USA"` rejected, `"US"` accepted (AC21); after each rejection the repository is empty (AC22) |
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
| change | `domain/QuoteRequest.java`, `dto/CreateQuoteRequest.java` | `country` is a **string**, not an enum, so `"IL"` returns 422 instead of 400 (unknown enum) |
| change | `service/QuoteService.java` | run the checks before pricing and saving; store the matching allowed deductible (`100`), not the raw input (`100.00`) |
| change | `error/GlobalExceptionHandler.java` | map `BusinessRuleException` → 422 |
| change | `application.properties` | age limits |

> The allowed deductibles are the keys of the deductible-factor table in `PricingRules`. That way they're
> defined once, and a 4th deductible is a config change only.

---

## Slice 4 — Input validation and strict JSON (400 / 415)

Goal: malformed or wrongly shaped requests return 400 (listing every invalid field), a wrong media type
returns 415, and the service is never called.

**Acceptance criteria:** AC13, AC14, AC15, AC16, AC17, AC18, AC19, AC22 (controller side), AC23, AC28, AC29 (400 part)

**Tests first** (all `@WebMvcTest`, mostly `@ParameterizedTest`)
| Test | Cases |
|------|-------|
| malformed JSON (AC14) | `{`, `not json`, empty body |
| missing / null required field (AC15) | each of petType, birthDate, plan, deductible, country, both missing and `null` |
| wrong types (AC16) | `"deductible": "250"`, `"petType": 1`, `"plan": true`, `"birthDate": 20230510`, `"country": 1` |
| unknown enums (AC17) | `petType: "BIRD"`, `plan: "GOLD"`, `petType: "dog"` |
| bad dates (AC18) | `"2023-02-30"`, `"2023-13-01"`, `"10/05/2023"`, `""` |
| breed (AC19) | rejected: `""`, `"   "`, 51 letters, `"Pug123"`, `"<script>"`, `"Löwchen"`; accepted: 50 letters, 50 letters with surrounding spaces, `"Shih-Tzu"`, `"Jack Russell"`, `"O'Malley"` |
| several invalid fields (AC29) | `plan` null + `breed` `"Pug123"` → 400, and the `errors` array has both fields |
| path id (AC13) | `GET /quotes/not-a-uuid` → 400 |
| media type (AC28) | `Content-Type: text/plain` → 415 `ProblemDetail` |
| unknown fields still ignored (AC31) | re-run the extra-field case after the strict-JSON config, which must not turn on "fail on unknown properties" |
| no side effects (AC22) | for every 400/415 case, `verifyNoInteractions(quoteService)` |
| error body (AC23) | 400/404/415/422/500 responses are `application/problem+json`, have `status`/`title`/`detail`, and no `trace`/`exception` field; when the service throws a `RuntimeException`, the response is a generic 500 detail |

**Files**
| Action | File | Why |
|--------|------|-----|
| change | `dto/CreateQuoteRequest.java` | `@NotNull` on required fields; the compact constructor strips `breed`, then `@Size(max=50)` + `@Pattern("^[A-Za-z]+(?:[ '-][A-Za-z]+)*$")` check the trimmed value (blank fails the pattern) |
| change | `controller/QuoteController.java` | `@Valid` on the body; `consumes = application/json` |
| change | `error/GlobalExceptionHandler.java` | `MethodArgumentNotValidException` → 400 with an `errors` array of `{field, message}`; `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` → 400; 415 comes from `ResponseEntityExceptionHandler`; catch-all 500 with a generic message |
| change | `application.properties` (or `config/JacksonConfig.java`) | strict Jackson: no scalar coercion (`"250"` → number), no numbers for enums, no numeric dates; unknown properties stay ignored |

> Risk: Jackson coerces by default (`"250"` → 250, `1` → enum ordinal, an integer → `LocalDate`).
> Spring Boot 4 ships Jackson 3, so the exact property or customizer names have to be checked during the slice.
> The WebMvc tests prove the config works because `@WebMvcTest` loads the Jackson auto-configuration.

---

## Slice 5 — End-to-end error flows and run instructions

Goal: prove that the full application wires validation, rules, errors and persistence together,
and make it easy for the reviewers to run the curl requests.

**Acceptance criteria:** confirms AC2, AC7/AC20, AC14, AC22, AC23 and AC28 end to end (no new behaviour)

**Tests first**
| Level | Test class | Cases |
|-------|-----------|-------|
| E2E | `QuoteApiIntegrationTest` (add) | malformed JSON → 400; `text/plain` → 415; pet too old → 422; after these, a valid POST still works and the rejected requests left nothing behind (H2 is shared across tests in the context, so compare the row count before and after, never assume an empty table); worked example PREMIUM/3y/Pug/250 → 49.14 through HTTP with a fixed `Clock` bean (`@TestConfiguration`) |

**Files**
| Action | File | Why |
|--------|------|-----|
| change | `src/test/.../QuoteApiIntegrationTest.java` | the flows above |
| create | `README.md` | how to run (`./mvnw spring-boot:run`, `PORT`); data in `./data` survives restarts, delete it to reset; H2 console with `--spring.profiles.active=local`; curl examples for 201/200/400/404/415/422; **Authentication** section: out of scope, endpoints are stateless and quotes hold no customer identity, Spring Security plugs in as a `SecurityFilterChain` in `config` with no controller or service changes |

---

## AC coverage

Every acceptance criterion from SPEC.md §7 and the slice(s) whose tests cover it.

| AC | Criterion (short) | Slice(s) |
|----|-------------------|----------|
| 1 | 201 + absolute `Location` + full body | 1 |
| 2 | Worked-example premiums | 1 (base price), 2, 5 |
| 3 | No breed → no breed factor | 2 |
| 4 | High-risk breed matching | 2 |
| 5 | `validUntil` = `createdAt` + 30 days | 1 |
| 6 | Round once, HALF_UP, scale 2 | 2 |
| 7 | 8-week minimum boundary | 3, 5 |
| 8 | 14-year maximum boundary | 3 |
| 9 | Future birthDate → 422 | 3 |
| 10 | Age-factor boundaries | 2 |
| 11 | GET existing → 200 | 1 |
| 12 | GET unknown → 404 | 1 |
| 13 | Non-UUID id → 400 | 4 |
| 14 | Malformed JSON → 400 | 4, 5 |
| 15 | Missing/null fields → 400 | 4 |
| 16 | Wrong types → 400 | 4 |
| 17 | Unknown enums → 400 | 4 |
| 18 | Bad dates → 400 | 4 |
| 19 | Breed rules (English letters only) | 4 |
| 20 | Deductible not allowed → 422; `100.00` → `100` | 3, 5 |
| 21 | Country not supported (incl. `"us"`) → 422 | 3 |
| 22 | Nothing stored on rejection | 3, 4, 5 |
| 23 | ProblemDetail, no stack trace | 4, 5 |
| 24 | Concurrent creates | 1 |
| 25 | Flyway schema + mapping round trip | 1 |
| 26 | `status` ACTIVE / EXPIRED | 1 |
| 27 | "Today" in the business zone | 2 |
| 28 | Wrong Content-Type → 415 | 4, 5 |
| 29 | 400 lists all fields; 422 first rule | 3 (422), 4 (400) |
| 30 | Breed trimmed | 2 |
| 31 | Unknown fields ignored | 1, 4 |

## Risks to check during implementation

All product questions are resolved (SPEC.md §8). These technical points still have to be verified:

1. **Boot 4 artifact names**: `spring-boot-starter-flyway` and `spring-boot-starter-data-jpa-test`. Checked against the Boot 4.1 BOM at the start of slice 1.
2. **Jackson 3 defaults**: coercion settings for slice 4. Also, unknown properties must stay ignored (AC31 guards this).
3. **H2 file lock**: only one app instance can open `./data/quotes` at a time. A second `spring-boot:run` would fail to start. This is acceptable and documented in the README.
4. **Test isolation**: the Surefire datasource override must apply to every test, or `./data` would be created during `./mvnw test`. Check after slice 1 that `./data` doesn't exist after a test run.