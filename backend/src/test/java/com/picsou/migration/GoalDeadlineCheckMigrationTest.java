package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies {@code V80__drop_goal_deadline_check.sql}.
 *
 * <p>V2's {@code CHECK (deadline > CURRENT_DATE)} was written as a rule about new goals, but
 * PostgreSQL re-evaluates a table CHECK on every UPDATE of the row whatever column changed, so
 * the day after a goal's deadline the constraint turned into "this row is frozen". The two
 * writes that suffer are {@code GoalService.extendHistory} and {@code extendHistoryByMonth},
 * which touch only {@code history_start_month} — the backfill actions whose whole point is a
 * goal that already ran its course.
 *
 * <p>Runs against real PostgreSQL via Testcontainers: the behaviour under test is when Postgres
 * evaluates a table CHECK, which no in-memory substitute reproduces.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class GoalDeadlineCheckMigrationTest {

    static {
        // See AccountLogoKeyMigrationTest: docker-java otherwise negotiates down to API 1.32,
        // which Engine >= 28 refuses, and that failure looks exactly like "no Docker here".
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The V80 migration test cannot be skipped here. "
                    + "Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static long goalId;
    private static String preMigrationRejection;

    /**
     * Brings the schema to V79 — a deployed instance — records what the constraint did there,
     * then applies V80.
     */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("79");

        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Alice') RETURNING id");
            // The CHECK forbids a past deadline at insert time, so the row starts out valid and
            // simply grows old — which is how every affected goal got there.
            goalId = insertReturningId(conn,
                "INSERT INTO goal (name, target_amount, deadline, member_id) "
                    + "VALUES ('Trip', 1000, CURRENT_DATE + 365, " + memberId + ") RETURNING id");

            try {
                exec(conn, "UPDATE goal SET deadline = CURRENT_DATE - 30 WHERE id = " + goalId);
                preMigrationRejection = null;
            } catch (SQLException e) {
                preMigrationRejection = e.getMessage();
            }
        }

        migrateTo("80");
    }

    @Test
    void theConstraintWasLiveOnUpdatesBeforeTheMigration() {
        assertThat(preMigrationRejection)
            .as("V2's CHECK is evaluated on UPDATE, not only on INSERT")
            .isNotNull()
            .contains("chk_goal_deadline");
    }

    @Test
    void dropsTheConstraint() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'chk_goal_deadline'"))
            .isZero();
    }

    @Test
    void aGoalWhoseDeadlineHasPassedStaysWritable() throws SQLException {
        try (Connection conn = connect()) {
            exec(conn, "UPDATE goal SET deadline = CURRENT_DATE - 30 WHERE id = " + goalId);

            // This is extendHistoryByMonth's write: only history_start_month changes, and under
            // V2's CHECK it failed for the sole reason that the row's deadline was in the past.
            assertThatCode(() -> exec(conn,
                "UPDATE goal SET history_start_month = '2020-01' WHERE id = " + goalId))
                .doesNotThrowAnyException();
        }

        assertThat(queryString("SELECT history_start_month FROM goal WHERE id = " + goalId))
            .isEqualTo("2020-01");
    }

    @Test
    void insertingAPastDeadlineIsNoLongerRejectedByTheDatabase() throws SQLException {
        // The rule now lives on @Future GoalRequest.deadline, which the controller validates on
        // create and on update alike; the database stops second-guessing it.
        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Bob') RETURNING id");
            assertThatCode(() -> insertReturningId(conn,
                "INSERT INTO goal (name, target_amount, deadline, member_id) "
                    + "VALUES ('Old', 10, CURRENT_DATE - 1, " + memberId + ") RETURNING id"))
                .doesNotThrowAnyException();
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .outOfOrder(true) // mirrors application.yml
            .load()
            .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static Connection readConn;

    private static String queryString(String sql) throws SQLException {
        if (readConn == null || readConn.isClosed()) {
            readConn = connect();
        }
        try (PreparedStatement ps = readConn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(queryString(sql));
    }

    @AfterAll
    static void closeReadConnection() throws SQLException {
        if (readConn != null) readConn.close();
    }
}
