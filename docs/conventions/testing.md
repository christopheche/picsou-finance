# Convention: Testing

## Structure

There is **no** `unit/` or `integration/` directory split. All tests live flat under `src/test/java/com/picsou/`, mirroring the source package structure.

```
src/test/java/com/picsou/
├── service/      # ~45 classes, roughly half the suite (GoalService, AccountService,
│                 #   FamilyService, MfaService, SecurityInsightService, HoldingCompute, …)
├── adapter/      # external-provider adapter tests (+ adapter/util)
├── controller/   # controller tests (pure Mockito by default -- see "Controller tests" below)
├── config/       # security / config tests (incl. the one @WebMvcTest slice, see below)
├── export/       # GDPR export tests
├── mcp/          # MCP access keys and scopes, plus mcp/tools/*ToolsTest
├── imports/      # CSV / broker file imports (+ imports/csv)
├── finary/       # Finary xlsx + API import (+ finary/dto)
├── ibkr/         # ibkr/client -- IBKR Flex parsing
├── migration/    # Flyway migrations on real PostgreSQL (Testcontainers) + schema↔entity check
├── repository/   # @DataJpaTest on H2 for custom queries
├── model/, dto/, port/, validation/, exception/   # a few classes each
```

Nearly every package under `src/main/java/com/picsou` has a mirror here, plus `migration/`,
which has no `src/main` counterpart. When adding coverage for a class, put the test in the
mirror package even if it is the first one there.

## Unit tests

**Stack:** Mockito + AssertJ — no Spring context loaded.

```java
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;

    @InjectMocks GoalService goalService;

    @Test
    void progressCalculation_onTrack() {
        // arrange
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        when(accountService.toResponse(account)).thenReturn(/* ... */);

        // act
        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // assert
        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
    }
}
```

### Patterns

- **`@ExtendWith(MockitoExtension.class)`** with `@Mock` and `@InjectMocks` — no `MockitoAnnotations.openMocks()`.
- **Lombok builders for test data** — `Account.builder()`, `Goal.builder()` etc. No test fixtures or mother objects.
- **AssertJ** for all assertions (`assertThat`, `isEqualByComparingTo`). No JUnit `assertEquals`.
- **No Spring context** in unit tests — pure Mockito mocking.

### Naming

- **Class:** `[Class]Test` (e.g., `GoalServiceTest`).
- **Method:** descriptive, underscore-separated, e.g., `progressCalculation_onTrack`. Not strictly `should_xxx_when_yyy`.
- **One test per behavior** — a test may have multiple assertions on the same logical result, but does not test multiple scenarios.

## Integration tests

When JPA is needed, use `@DataJpaTest` with **H2 in-memory** — the default, and enough
for repository queries and entity mapping.

```bash
# H2 auto-configures; no external database needed
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=SomeRepoTest
```

H2 is on the test classpath via `spring-boot-starter-test`.

### Testcontainers — only for real-PostgreSQL behaviour

H2 cannot run the Flyway chain: the migrations are PostgreSQL-flavoured
(`CREATE TYPE ... AS ENUM`, `split_part()`, partial indexes). Real PostgreSQL via
Testcontainers is therefore the only way to verify three kinds of behaviour, and it is
reserved for exactly those:

1. **Data-mutating migrations** — one that rewrites existing rows rather than only adding
   structure (`WalletEvmMigrationTest`, `AccountLogoKeyMigrationTest`,
   `DuplicateSyncAccountMergeMigrationTest`, `TradeRepublicValuationMigrationTest`,
   `RealEstateValuationMigrationTest`).
2. **Entity ↔ schema validation** — `SchemaMappingValidationTest` boots the persistence unit
   with `ddl-auto=validate` against the Flyway-migrated schema, the same check the
   application runs at startup. It exists because a `CHAR(2)` column shipped through a green
   H2 build and crash-looped the container.
3. **ORM behaviour a mock cannot show** on an entity whose schema only exists in Flyway —
   lazy proxies, `clearAutomatically` detaching, unique-key violations
   (`AccountOwnershipReplaceIntegrationTest`, a `@DataJpaTest` with
   `@AutoConfigureTestDatabase(replace = NONE)` pointed at the container).

Reach for it *only* for those. Everything else stays on Mockito or H2; a container costs
seconds of wall clock per class. A `@DataJpaTest` on H2 with a hand-rolled schema
(`@Sql("classpath:sql/...")`, Flyway disabled) is the cheaper option whenever the behaviour
under test is transactional rather than PostgreSQL-specific — see `IbkrStatusWriterTest`.

Pattern for migrations (see `WalletEvmMigrationTest`): no Spring context — drive Flyway and
JDBC directly, migrating in two steps so the seeded data is what the migration under test
actually operates on.

```java
@Testcontainers
class V99SomeMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("98");   // the schema a deployed instance is already on
        // ... seed rows representing real pre-migration data, incl. negative controls ...
        migrateTo("99");   // apply the migration under test, alone
    }
}
```

Assert both directions: the rows that must change, **and** the rows that must not.

Three things the class must do, all learned the hard way:

- **Gate on Docker** with `@EnabledIf("dockerAvailable")` (backed by
  `DockerClientFactory.instance().isDockerAvailable()`), so a machine without a Docker
  socket skips this class instead of failing the whole suite. It must be a JUnit
  `ExecutionCondition` — a `@BeforeAll` assumption runs *after* the Testcontainers
  extension has already tried to start the container.
- **Pin `api.version=1.44`, in two places.** Otherwise docker-java negotiates down to API
  1.32, which Engine ≥ 28 refuses — and that surfaces as the *same* "Could not find a valid
  Docker environment" error a Docker-less machine gives, so the guard above would quietly
  skip the test on a perfectly capable host. `pom.xml` sets it via surefire
  `systemPropertyVariables` so it applies process-wide before *any* Testcontainers class
  initializes; the test class also sets it in a static block so IDE and failsafe runs
  (which never read surefire config) work too. A classpath `testcontainers.properties` is
  **not** honored for this — tested. Sets the floor at Docker Engine ≥ 25.0.

- **Make CI refuse to skip.** A skip is invisible in a green build, so `ci.yml` sets
  `PICSOU_REQUIRE_DOCKER_TESTS=true` and `dockerAvailable()` throws instead of returning
  false when it is set. Without this, any Docker drift on the runner silently converts the
  PostgreSQL coverage of data-mutating migrations into a permanently green no-op.

The three interlock: the guard alone turns a config problem into a silent pass, and the
API pin alone makes Docker-less machines fail the whole suite.

Locally, the gated classes above are the **only** legitimate source of skips: with Docker
reachable the Skipped count is 0, without it every `@EnabledIf("dockerAvailable")` class
sits out — 7 classes and a few dozen tests at the time of writing. Recount rather than
trust that figure:

```bash
grep -rl 'EnabledIf("dockerAvailable")' src/test/java \
  | xargs grep -hoE '@(Test|ParameterizedTest)\b' | wc -l
```

Any other non-zero Skipped count is a test silently bypassing itself and needs a look.

Also order any test that mutates the shared seeded dataset **last**
(`@TestMethodOrder` + `@Order`) — JUnit's default method order is deliberately
unspecified, so otherwise the other tests may assert against post-mutation state. Seed
once in a static `@BeforeAll`; never let two test methods each call `migrateTo` with
different targets, because Flyway never migrates backwards and whichever runs second no
longer tests what it claims to.

## Frontend tests

- **Unit tests:** Vitest (`vitest`) with `@testing-library/react` and `jsdom`.
- **E2E tests:** Playwright (`@playwright/test`).
- Run commands:
  ```bash
  bunx vitest run         # unit tests
  bun run test:e2e        # E2E tests
  ```

## Running tests

Backend Maven runs enforce Java 21 during `validate`; set `JAVA_HOME` to a JDK 21
installation before running backend tests locally.

```bash
# Backend — all tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test

# Backend — single test class
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest

# Backend — single test method
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest=GoalServiceTest#progressCalculation_onTrack
```

## Current coverage

The suite is on the order of **1,600 test methods across ~160 classes**, `service/` holding
roughly half. A hard number in a convention file goes stale within weeks — the last one here
undercounted by 2x before anyone noticed — so treat this as an order of magnitude and recount
when it matters:

```bash
# authoritative: tests run / skipped, from the surefire summary
mvn -q test 2>&1 | grep 'Tests run:' | tail -1
# cheap approximation, straight from the tree
grep -rhoE '@(Test|ParameterizedTest)\b' src/test/java | wc -l
find src/test/java -name '*Test.java' | wc -l
```

The surefire total is lower than the annotation count whenever the Docker-gated classes skip
(see above); a `@ParameterizedTest` pushes it the other way.

When adding coverage, prioritize:

1. **Service-layer unit tests** — mock dependencies, test business logic.
2. **Repository custom queries** — `@DataJpaTest` for non-trivial JPQL.
3. **Controller tests** — see the next section.
4. **Data-mutating migrations** — Testcontainers, per the section above.

## Controller tests

The default is **pure Mockito** (`@Mock` + `@InjectMocks` or a hand-built constructor), calling
controller methods directly and asserting on the returned DTO / `ResponseEntity`. Controllers
in this project are thin enough that a Spring context buys nothing for their own logic. Assert
that the member id comes from `UserContext`, which is the scoping contract at that layer
(`AccountControllerTest`, `GoalControllerTest`, `AccessKeyControllerTest`, …).

Two narrow exceptions are ratified, each for a behaviour that a direct method call cannot
observe:

- **`MockMvcBuilders.standaloneSetup(controller)` — no Spring context** — when the behaviour
  under test is request-level plumbing: `@Valid` bean validation on the request body and the
  status/`ProblemDetail` mapping done by `GlobalExceptionHandler`
  (`.setControllerAdvice(new GlobalExceptionHandler())`). Still `@ExtendWith(MockitoExtension.class)`,
  still mocked collaborators — only the dispatcher is real. Examples: `DegiroControllerTest`,
  `BoursoControllerTest`, `BourseDirectControllerTest`, `AmundiControllerTest`.
- **A `@WebMvcTest` slice** — only for Spring Security filter/header wiring that exists solely
  as configuration, so there is no method to call. The one instance is
  `SecurityConfigHstsHeaderTest`, which proves `app.hsts-enabled` really gates the
  `Strict-Transport-Security` writer (an inverted condition there is a browser lockout). It
  declares its own `@SpringBootConfiguration` (`HstsSliceTestApplication`) in the test package
  so the slice does not pick up `PicsouApplication` and its `@EnableJpaAuditing`, and mocks
  every collaborator of the filter chain with `@MockitoBean`. Copy that shape rather than
  reaching for `@SpringBootTest`, which is not used anywhere in the suite.

A new controller test that needs either exception must say why in its class Javadoc, as
those four do.

## Don'ts

- **Never load a full Spring context (`@SpringBootTest`) in tests** — pure Mockito. Use
  `@DataJpaTest` only for JPA integration tests, and a `@WebMvcTest` slice only per the
  "Controller tests" section above.
- **Never use `MockitoAnnotations.openMocks()`** — use `@ExtendWith(MockitoExtension.class)`.
- **Never use JUnit `assertEquals`** — always AssertJ (`assertThat`).
- **Never use `@Autowired` in tests** outside a `@DataJpaTest` integration test or the
  `@WebMvcTest` slice described above.
- **Never create test fixtures or "mother objects"** — use Lombok builders directly.
- **Never `assumeTrue` / `@Disabled` your way past an environment dependency** without the
  Docker-style escape hatch: a skipped test is invisible in a green build. Resolve the
  dependency (a repo file, a fixture) deterministically and fail when it is missing —
  `SecurityConfigHstsParsingTest` walks up from the module directory to find
  `docker/entrypoint.sh` instead of assuming the working directory.
