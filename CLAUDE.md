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
- Persistence: in-memory, thread-safe repository implementations (`ConcurrentHashMap`, atomic operations). No database dependency. Services depend only on the repository interface, so a JPA/Postgres implementation can be added without changing services.
- Constructor injection only.
- Time only from the injected `Clock` bean. Never call `now()` without it.
- Timestamps are UTC `Instant`s. Calendar-date rules (e.g. pet age) use today's date in UTC (`LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)`), never the JVM default zone.
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
- Service: plain JUnit 5 + AssertJ, fixed `Clock`, real in-memory repository, no Spring context, no mocks.
- Repository: plain JUnit, including concurrent writes and duplicate ids.
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