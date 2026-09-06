package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@code V66__real_estate_valuation_and_ownership.sql} against real PostgreSQL.
 *
 * <p>Two things need a live database to check. First, that existing properties survive the
 * migration untouched: the new columns are additive and {@code account_ownership} starts
 * empty precisely so that no backfill is needed, and a property recorded before V66 must
 * still read back identically afterwards. Second, that the constraints actually bite —
 * they are the last line of defence for the ownership arithmetic, and H2 would not
 * reproduce the enum-typed schema this chain builds.
 *
 * <p>The seed runs once in {@code @BeforeAll} and the tests are explicitly ordered, per
 * {@code docs/conventions/testing.md}. Both matter here: the container is shared and Flyway never
 * migrates backwards, so a second method calling {@code migrateTo} with its own target would
 * decide, by JUnit's unspecified default method order, whether the "pre-existing property"
 * actually predates V66 — and the whole point of this class is that it does.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealEstateValuationMigrationTest {

    static {
        // Same reason as WalletEvmMigrationTest: docker-java negotiates down to an API
        // version modern engines refuse, which is indistinguishable from "no Docker".
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
                    + "The V66 migration test cannot be skipped -- it is the only check that "
                    + "existing properties survive the ownership change. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static long seededMemberId;
    private static long seededAccountId;

    /**
     * Brings the schema to the state a deployed instance is in before this change, seeds a property
     * recorded under that schema, then applies V66 alone. Seeding here rather than inside a test
     * method is what makes "pre-existing" true no matter which test runs first.
     */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        // The trailing "?" makes the target lenient: it means "everything up to 65, and do
        // not fail if that exact version is absent". It is, since the crypto branch's V65 was
        // renumbered to V72 when it merged around main's own V64 -- a hard "65" made this the
        // only test in the suite that a renumber elsewhere could break.
        migrateTo("65?");

        try (Connection conn = connect()) {
            seededMemberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Alice') RETURNING id");
            seededAccountId = insertReturningId(conn,
                "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id) "
                    + "VALUES ('Maison', 'REAL_ESTATE'::account_type, 'EUR', 400000, true, "
                    + seededMemberId + ") RETURNING id");
            exec(conn,
                "INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, surface_area, "
                    + "address, property_type) VALUES (" + seededAccountId + ", " + seededMemberId
                    + ", 300000, 120, '1 rue de la Paix', 'HOUSE')");
        }

        migrateTo("66");
    }

    /**
     * Read-only, and first: the row it inspects was written before V66 ran, so the values below are
     * the migration's backfill of existing rows, not the column defaults an INSERT would pick up.
     */
    @Test
    @Order(1)
    void v66_preservesExistingProperties() throws SQLException {
        long accountId = seededAccountId;

        try (Connection conn = connect()) {
            // The pre-existing property is intact, and V66 backfilled the new NOT NULL columns.
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT purchase_price, surface_area, address, property_type, country, "
                    + "valuation_mode, garage_count, has_garden FROM real_estate_metadata WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal("purchase_price")).isEqualByComparingTo("300000");
                    assertThat(rs.getBigDecimal("surface_area")).isEqualByComparingTo("120");
                    assertThat(rs.getString("address")).isEqualTo("1 rue de la Paix");
                    assertThat(rs.getString("property_type")).isEqualTo("HOUSE");
                    assertThat(rs.getString("country")).isEqualTo("FR");
                    // ESTIMATED by default: an existing property starts tracking the market
                    // rather than silently freezing at whatever was typed in years ago.
                    assertThat(rs.getString("valuation_mode")).isEqualTo("ESTIMATED");
                    assertThat(rs.getShort("garage_count")).isZero();
                    assertThat(rs.getBoolean("has_garden")).isFalse();
                }
            }

            // No backfill: absence of rows is what means "the owner holds 100%".
            assertThat(count(conn, "SELECT COUNT(*) FROM account_ownership")).isZero();
            assertThat(count(conn, "SELECT COUNT(*) FROM property_valuation")).isZero();
        }
    }

    /** Writes rows, so it runs after the read-only check above. */
    @Test
    @Order(2)
    void v66_enforcesShareAndValuationConstraints() throws SQLException {
        long memberId = seededMemberId;
        long accountId = seededAccountId;

        try (Connection conn = connect()) {
            // A share must be a real fraction of something.
            assertThatThrownBy(() -> exec(conn,
                "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
                    + accountId + ", " + memberId + ", 0)"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> exec(conn,
                "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
                    + accountId + ", " + memberId + ", 150)"))
                .isInstanceOf(SQLException.class);

            exec(conn, "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
                + accountId + ", " + memberId + ", 50)");
            // One row per member per account, so a split cannot double-count someone.
            assertThatThrownBy(() -> exec(conn,
                "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
                    + accountId + ", " + memberId + ", 50)"))
                .isInstanceOf(SQLException.class);

            // One valuation per property per day: repeated refreshes correct today's figure
            // rather than filling the history with near-identical rows.
            exec(conn, "INSERT INTO property_valuation (account_id, member_id, valued_at, "
                + "estimated_value, provider) VALUES (" + accountId + ", " + memberId
                + ", DATE '2026-08-01', 420000, 'CEREMA_DV3F')");
            assertThatThrownBy(() -> exec(conn,
                "INSERT INTO property_valuation (account_id, member_id, valued_at, estimated_value, provider) "
                    + "VALUES (" + accountId + ", " + memberId + ", DATE '2026-08-01', 430000, 'CEREMA_DV3F')"))
                .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> exec(conn,
                "INSERT INTO property_valuation (account_id, member_id, valued_at, estimated_value, "
                    + "provider, confidence) VALUES (" + accountId + ", " + memberId
                    + ", DATE '2026-08-02', 430000, 'CEREMA_DV3F', 'PROBABLY')"))
                .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> exec(conn,
                "UPDATE real_estate_metadata SET valuation_mode = 'GUESSED' WHERE account_id = " + accountId))
                .isInstanceOf(SQLException.class);
        }
    }

    /** Deletes an account, so it runs last. It seeds its own, separate from the migrated one. */
    @Test
    @Order(Integer.MAX_VALUE)
    void v66_cascadesOnAccountDeletion() throws SQLException {
        try (Connection conn = connect()) {
            long memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Bob') RETURNING id");
            long accountId = insertReturningId(conn,
                "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id) "
                    + "VALUES ('Appart', 'REAL_ESTATE'::account_type, 'EUR', 200000, true, "
                    + memberId + ") RETURNING id");
            exec(conn, "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ("
                + accountId + ", " + memberId + ", 100)");
            exec(conn, "INSERT INTO property_valuation (account_id, member_id, valued_at, "
                + "estimated_value, provider) VALUES (" + accountId + ", " + memberId
                + ", CURRENT_DATE, 210000, 'CEREMA_DV3F')");

            exec(conn, "DELETE FROM account WHERE id = " + accountId);

            // Deleting a property must not strand its shares or its valuation history.
            assertThat(count(conn, "SELECT COUNT(*) FROM account_ownership WHERE account_id = " + accountId))
                .isZero();
            assertThat(count(conn, "SELECT COUNT(*) FROM property_valuation WHERE account_id = " + accountId))
                .isZero();
        }
    }

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

    private static long count(Connection conn, String sql) throws SQLException {
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
}
