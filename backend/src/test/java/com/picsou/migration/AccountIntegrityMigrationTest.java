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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the three migrations that close the holes V77 left around {@code account}:
 *
 * <ul>
 *   <li>{@code V81__account_deleted_at_timestamptz.sql} — the one Instant column V30 declared
 *       without a time zone, against the convention every other one follows;</li>
 *   <li>{@code V82__unique_live_synced_account.sql} — the unique index behind the upsert key
 *       fifteen connector call sites treat as unique through an {@code Optional} query;</li>
 *   <li>{@code V83__cleanup_orphan_shared_resources.sql} — the {@code shared_resource} rows V77
 *       left pointing at accounts it merged away, the one child table it did not re-point
 *       because no foreign key ties it to {@code account}.</li>
 * </ul>
 *
 * <p>Runs against real PostgreSQL via Testcontainers: a partial unique index with
 * {@code NULLS NOT DISTINCT}, a type change through {@code USING ... AT TIME ZONE} and the
 * warning-guarded {@code DO} block are Postgres behaviour, not portable SQL.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class AccountIntegrityMigrationTest {

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
                    + "The V81-V83 migration test cannot be skipped here. "
                    + "Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    private static long memberId;
    private static long liveSyncedId;
    private static long deletedSyncedId;
    private static long sharedLiveAccountId;
    private static long keptShareId;
    private static long orphanShareId;
    private static long orphanGoalShareId;

    /** Brings the schema to V80 — a deployed instance — seeds the damage, then applies V81-V83. */
    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("80");

        try (Connection conn = connect()) {
            memberId = insertReturningId(conn,
                "INSERT INTO family_member (display_name) VALUES ('Alice') RETURNING id");

            liveSyncedId = insertAccount(conn, "Ledger BTC", "wallet_bitcoin_2", false, "BTC", null);
            // Same key, soft-deleted: the account a user removed and later reconnected. The
            // partial index has to keep tolerating this pair.
            deletedSyncedId = insertAccount(conn, "Old Ledger BTC", "wallet_bitcoin_2", false, "BTC",
                "2026-09-05 10:00:00");

            sharedLiveAccountId = insertAccount(conn, "Livret", "bank_1", false, "Bank A", null);
            keptShareId = insertReturningId(conn,
                "INSERT INTO shared_resource (owner_member_id, resource_type, resource_id) VALUES ("
                    + memberId + ", 'ACCOUNT', " + sharedLiveAccountId + ") RETURNING id");
            // What V77 leaves behind: a share addressing an account id that no longer exists.
            orphanShareId = insertReturningId(conn,
                "INSERT INTO shared_resource (owner_member_id, resource_type, resource_id) VALUES ("
                    + memberId + ", 'ACCOUNT', 999999) RETURNING id");
            // A dangling GOAL share is a different story with a different cause: V83 must not
            // widen its scope to rows V77 never touched.
            orphanGoalShareId = insertReturningId(conn,
                "INSERT INTO shared_resource (owner_member_id, resource_type, resource_id) VALUES ("
                    + memberId + ", 'GOAL', 999999) RETURNING id");
        }

        migrateTo("83");
    }

    // ─── V81 ──────────────────────────────────────────────────────────────────

    @Test
    void deletedAtBecomesTimestamptz() throws SQLException {
        assertThat(queryString("SELECT data_type FROM information_schema.columns "
            + "WHERE table_name = 'account' AND column_name = 'deleted_at'"))
            .isEqualTo("timestamp with time zone");
    }

    @Test
    void existingDeletionTimesAreReadBackAsTheUtcInstantHibernateStored() throws SQLException {
        // Hibernate binds Instant through a UTC calendar, so the tz-less value already in the
        // column is a UTC wall clock. The conversion must not shift it by the server's offset.
        assertThat(queryString("SELECT to_char(deleted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') "
            + "FROM account WHERE id = " + deletedSyncedId))
            .isEqualTo("2026-09-05 10:00:00");
    }

    // ─── V82 ──────────────────────────────────────────────────────────────────

    @Test
    void createsTheUniqueIndexBehindTheUpsertKey() throws SQLException {
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE tablename = 'account' "
            + "AND indexname = 'uk_account_member_external_provider'"))
            .as("no duplicate remained after V77, so the guarded CREATE must have run")
            .isEqualTo(1);
    }

    @Test
    void rejectsASecondLiveRowForTheSameConnectorKey() {
        // The concurrency the index exists for: the 03:00 scheduled sync and a manual one both
        // read Optional.empty() and both insert.
        assertThatThrownBy(() -> {
            try (Connection conn = connect()) {
                insertAccount(conn, "Ledger BTC (dup)", "wallet_bitcoin_2", false, "BTC", null);
            }
        }).isInstanceOf(SQLException.class)
            .hasMessageContaining("uk_account_member_external_provider");
    }

    @Test
    void stillAllowsTheRowsV77DeliberatelyKeptApart() throws SQLException {
        try (Connection conn = connect()) {
            // Two banks handing out the same opaque id: V77 refuses to merge them, so the index
            // must refuse to reject them.
            assertThatCode(() -> insertAccount(conn, "Compte B", "bank_1", false, "Bank B", null))
                .doesNotThrowAnyException();
            // external_account_id is free text on manual accounts (V75).
            assertThatCode(() -> {
                insertAccount(conn, "Cash A", "my-notes", true, null, null);
                insertAccount(conn, "Cash B", "my-notes", true, null, null);
            }).doesNotThrowAnyException();
            // A soft-deleted predecessor and its live successor share the key on purpose.
            assertThat(count("SELECT count(*) FROM account WHERE external_account_id = 'wallet_bitcoin_2'"))
                .isEqualTo(2);
        }
    }

    @Test
    void treatsTwoRowsWithoutAProviderAsOneKey() throws SQLException {
        // NULLS NOT DISTINCT: V77's GROUP BY considered them one group, and an index that
        // considered them two would leave exactly the duplicates V77 had to repair.
        try (Connection conn = connect()) {
            insertAccount(conn, "No provider", "ext_no_provider", false, null, null);
            assertThatThrownBy(() -> insertAccount(conn, "No provider bis", "ext_no_provider", false, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_account_member_external_provider");
        }
    }

    // ─── V83 ──────────────────────────────────────────────────────────────────

    @Test
    void removesSharesPointingAtAccountsThatNoLongerExist() throws SQLException {
        assertThat(count("SELECT count(*) FROM shared_resource WHERE id = " + orphanShareId)).isZero();
    }

    @Test
    void keepsSharesThatStillResolve() throws SQLException {
        assertThat(count("SELECT count(*) FROM shared_resource WHERE id = " + keptShareId)).isEqualTo(1);
    }

    @Test
    void leavesResourceTypesV77NeverTouchedAlone() throws SQLException {
        assertThat(count("SELECT count(*) FROM shared_resource WHERE id = " + orphanGoalShareId))
            .as("V83 repairs V77's damage, not every dangling row in the table")
            .isEqualTo(1);
    }

    @Test
    void keepsSharesOnSoftDeletedAccounts() throws SQLException {
        // A soft-deleted account still has a row, so its share resolves and must survive: the
        // cleanup keys on existence, not on visibility.
        try (Connection conn = connect()) {
            long id = insertReturningId(conn,
                "INSERT INTO shared_resource (owner_member_id, resource_type, resource_id) VALUES ("
                    + memberId + ", 'ACCOUNT', " + deletedSyncedId + ") RETURNING id");
            assertThat(count("SELECT count(*) FROM shared_resource WHERE id = " + id)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM account WHERE id = " + liveSyncedId)).isEqualTo(1);
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

    private static long insertAccount(
        Connection conn, String name, String externalId, boolean manual, String provider, String deletedAt
    ) throws SQLException {
        return insertReturningId(conn,
            "INSERT INTO account (name, type, provider, currency, current_balance, external_account_id, "
                + "is_manual, member_id, deleted_at) VALUES ('" + name + "', 'CRYPTO'::account_type, "
                + (provider == null ? "NULL" : "'" + provider + "'") + ", 'EUR', 100, '" + externalId
                + "', " + manual + ", " + memberId + ", "
                + (deletedAt == null ? "NULL" : "'" + deletedAt + "'") + ") RETURNING id");
    }

    private static long insertReturningId(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
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
