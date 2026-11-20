# interviewprep

Java 21 · Spring Boot 4 · Maven wrapper.

## Commands
- Run: `./mvnw spring-boot:run` (port from `PORT` env, default 8080)
- All tests: `./mvnw test`
- One class: `./mvnw test -Dtest=ItemServiceTest`

## Architecture
- Package by layer under `com.hila.interviewprep`: `controller`, `service`, `repository`, `dto`, `domain`, `error`, `config`.
- Flow: Controller -> Service -> Repository interface. Controllers only map HTTP <-> DTOs and call the service.
- Request/response DTOs are records and never leave the controller layer. Services work with domain objects.
- Persistence: H2 file database (`./data`, gitignored) through Spring Data JPA. Tests use an in-memory H2.
  - Services depend only on the `QuoteRepository` interface, which takes and returns domain objects. JPA entities and Spring Data interfaces stay inside `repository` and never reach the service or the controller.
  - Switching to Postgres must only need a driver and datasource config change, with no service changes.
  - Schema changes are Flyway migrations in `src/main/resources/db/migration` (`V<n>__<description>.sql`). Never edit a migration that has already been applied; add a new one. Hibernate only validates (`ddl-auto=validate`).
  - Enums are stored as strings (`@Enumerated(STRING)`), never ordinals. Money is `DECIMAL(10,2)`.
  - H2 console only under the `local` profile.
- Constructor injection only.
- Time only from the injected `Clock` bean. Never call `now()` without it.
- Timestamps are UTC `Instant`s. Calendar-date rules (e.g. pet age) use today's date in the business zone (`quote.business-zone`, default `America/New_York`), never the JVM default zone.
- Money is `BigDecimal` (create from String, compare with `compareTo`, max 2 decimals), never `double`.
- IDs are server-generated UUIDs.

## API and errors
- JSON input is strict: malformed JSON, wrong types (e.g. `"100"` for a number) and unknown enum values return 400.
- All errors go through one `@RestControllerAdvice` returning `ProblemDetail`. Never leak stack traces.
- Status codes: 201 + `Location` on create, 200 on read, 400 invalid input, 404 not found, 409 conflict/duplicate, 422 valid input that breaks a business rule, 500 unexpected.
- Bean Validation on request DTOs for shape. Business rules live in the service.

## Testing
- Before coding, restate the acceptance criteria and map each one to a test.
- Write the failing test first, then the minimal code to pass it.
- Service: plain JUnit 5 + AssertJ, fixed `Clock`, a test-only in-memory `QuoteRepository` fake (`ConcurrentHashMap`), no Spring context, no mocks.
- Repository: `@DataJpaTest` against in-memory H2 with the Flyway migrations applied. Cover the full mapping round trip (money scale, enums as strings, timestamps) and concurrent writes.
- Tests never touch the `./data` file database: Surefire points `spring.datasource.url` at in-memory H2.
- Controller: `@WebMvcTest` with the service mocked. Assert status, headers and body.
- End-to-end: `@SpringBootTest` + MockMvc for the main flows only.
- Use `@ParameterizedTest` for input-validation matrices.
- Always cover: missing/null/blank fields, wrong types, malformed JSON, boundary values, unknown ids, duplicates, and concurrent writes when state is shared.

## Workflow
- For changes touching more than one file: plan first (files to change and test cases), then wait for approval. One-line fixes can go straight through.
- Minimal code: implement only what the acceptance criteria require.
- Never modify or delete an existing test to make it pass. Stop and explain.
- Ask before adding any dependency.
- After each change run `./mvnw test` and report pass/fail counts.
- End each change by appending one entry to the `## Log` section in `SPEC.md`: what changed, the files changed and why, and the tests run. Create the section if it doesn't exist.