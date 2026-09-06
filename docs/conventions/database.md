# Convention: Database

## Schema ownership

**Flyway owns the schema.** Hibernate is set to `ddl-auto: validate` only — it checks that entities match the database but never modifies it.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    # Feature branches number their migrations independently, so a lower version
    # regularly lands after a higher one has already shipped. Without this,
    # validate-on-migrate rejects the late arrival and the app refuses to start.
    out-of-order: true
```

**Never** use `ddl-auto: create`, `update`, or `create-drop`. Every schema change requires a new Flyway migration file.

## Migrations

Migration files live in `backend/src/main/resources/db/migration/`.

### Naming

```
V{n}__description.sql
```

- `V` prefix for versioned migrations.
- `{n}` is an integer, one higher than the highest version already in the folder
  (V83 at the time of writing). Numbers are never reused and never renumbered once
  a migration has shipped — Flyway checksums the file, and a renumbered migration is
  a boot failure on every instance that already applied it.
- Double underscore before the description.
- Description in snake_case.

### Existing migrations (V1-V83)

| File | Content |
|------|---------|
| `V1__init_schema.sql` | Core tables: `app_user`, `account`, `balance_snapshot`, `account_type` enum |
| `V2__goals.sql` | `goal` table + `goal_account` join table |
| `V3__requisitions.sql` | `requisition` table for bank connections |
| `V4__trade_republic_session.sql` | `trade_republic_session` |
| `V5__tr_refresh_token.sql` | Refresh token column for TR sessions |
| `V6__rename_gocardless_column.sql` | Rename `gocardless_account_id` to `external_account_id` |
| `V7__goal_month_override.sql` | `goal_month_override` table |
| `V9__crypto_exchange_session.sql` | `crypto_exchange_session` table |
| `V10__wallet_address.sql` | `wallet_address` table |
| `V11__account_holding.sql` | `account_holding` table |
| `V12__transactions.sql` | `transaction` table |
| `V13__goal_manual_contribution.sql` | `goal_manual_contribution` table |
| `V14__price_snapshot.sql` | `price_snapshot` table (price cache history) |
| `V15__widen_encrypted_columns.sql` | Widen encrypted secret columns for AES-GCM payloads |
| `V16__finary_session.sql` | `finary_session` table |
| `V17__requisition_last_synced_at.sql` | `last_synced_at` column on `requisition` |
| `V18__snapshot_invested_amount.sql` | `invested_amount` column on `balance_snapshot` |
| `V19__real_estate_and_debts.sql` | Real-estate accounts + `debt` linkage |
| `V20__create_family_system.sql` | `family_member`, `sharing_settings`, `shared_resource` |
| `V21__migrate_existing_data.sql` | Backfill `member_id` on all existing financial rows |
| `V22__make_member_id_not_null.sql` | Enforce `member_id NOT NULL` after backfill |
| `V23__bourso_session.sql` | `bourso_session` table (BoursoBank sidecar) |
| `V24__manual_transactions.sql` | Manual transaction support |
| `V25__setup_state.sql` | First-launch Setup Wizard state |
| `V26__setup_audit.sql` | Setup audit log |
| `V27__loan_extra_fields.sql` | Loan amortization fields on `account` |
| `V28__mfa_and_persistent_sessions.sql` | TOTP 2FA + rotating Remember-Me tokens |
| `V29__app_user_token_version.sql` | `token_version` claim column for stateless invalidation |
| `V30__account_soft_delete.sql` | Soft-delete (`deleted_at`) on `account` |
| `V31__price_cleanup_gate.sql` | Gate column controlling price-snapshot cleanup |
| `V32__goal_history_start.sql` | `history_start` column anchoring goal trajectory charts |
| `V36__transaction_security_name.sql` | `security_name` column on `transaction` |
| `V37__access_keys.sql` | `access_key` table: scoped keys for the embedded MCP server |
| `V38__backfill_tr_crypto_transaction_tickers.sql` | Backfill tickers on manual TR on-platform crypto transactions |
| `V50__account_bank_logo.sql` | `logo_url` on `account` and `requisition` |
| `V51__requisition_oauth_state.sql` | `oauth_state` nonce + unique index on `requisition` |
| `V52__fix_negative_loan_balances.sql` | Data fix: LOAN balances stored positive |
| `V53__transaction_fees.sql` | `fees` column on `transaction` |
| `V54__wallet_ethereum_to_evm.sql` | Convert ETHEREUM wallets to the EVM fan-out |
| `V55__wallet_evm_account_name.sql` | Rename the accounts V54 converted |
| `V56__persistent_session_previous_token.sql` | Grace window for concurrent Remember-Me restores |
| `V57__ibkr_connection.sql` | `ibkr_connection` table (IBKR Flex Web Service) |
| `V58__account_cash_balance.sql` | `cash_balance` column on `account` |
| `V59__bourse_direct_session.sql` | `bourse_direct_session` table |
| `V60__bourse_direct_integration.sql` | Bourse Direct integration settings |
| `V61__harden_bourse_direct_sync.sql` | Constraints on the Bourse Direct sync state |
| `V62__backfill_bourse_direct_valuations.sql` | Backfill EUR valuations for early Bourse Direct holdings |
| `V63__constrain_bourse_direct_sync_errors.sql` | CHECK bounding the Bourse Direct sync error state |
| `V64__backfill_trade_republic_valuations.sql` | Backfill `provider_value_eur` on TR holdings |
| `V66__real_estate_valuation_and_ownership.sql` | `property_valuation`, `account_ownership`, extended `real_estate_metadata` |
| `V67__real_estate_bathrooms.sql` | `bathrooms` column on `real_estate_metadata` |
| `V68__widen_requisition_institution_id.sql` | Widen `requisition.institution_id` to VARCHAR(255) |
| `V69__account_type_employee_savings.sql` | `EMPLOYEE_SAVINGS` account type |
| `V70__amundi_session.sql` | `amundi_session` table |
| `V71__degiro_session.sql` | `degiro_session` table |
| `V73__crypto_exchange_session_optional_secret.sql` | `api_secret` becomes nullable (Meria's single-key auth) |
| `V74__crypto_exchange_position.sql` | `crypto_exchange_position` table (per-product breakdown) |
| `V75__account_logo_key.sql` | `logo_key` column on `account` |
| `V76__account_requisition_link.sql` | `requisition_id` column on `account` |
| `V77__merge_duplicate_sync_accounts.sql` | Data fix: merge the duplicate synced accounts a missing guard created |
| `V78__bourso_session.sql` | Rebuild `bourso_session` to the sidecar session shape |
| `V79__account_type_french_savings.sql` | French regulated passbook account types |
| `V80__drop_goal_deadline_check.sql` | Drop `chk_goal_deadline` so a past-deadline goal stays writable |
| `V81__account_deleted_at_timestamptz.sql` | `account.deleted_at` TIMESTAMP → TIMESTAMPTZ |
| `V82__unique_live_synced_account.sql` | Partial unique index on `(member_id, external_account_id, provider)` |
| `V83__cleanup_orphan_shared_resources.sql` | Data fix: drop the `shared_resource` rows V77 orphaned |

#### Absent versions

Flyway does not require contiguous versions, and `out-of-order: true` means a number left free
today can still be taken tomorrow. The gaps are:

| Missing | Why |
|---------|-----|
| V8 | Skipped — never written, never rolled into another migration |
| V33-V35, V39-V49 | Reserved by the 1.1.0 branch, which owns V33-V47 (see the header of `V50__account_bank_logo.sql`); main only ever used V36-V38 out of that range, and numbering resumed at V50 |
| V65, V72 | Never existed on any branch. The crypto branch's migrations were renumbered around main's own V64 and V71 and landed as V73/V74, leaving these two numbers unused |

### Writing a new migration

1. Create `V{next}__descriptive_name.sql` in `db/migration/`.
2. Use plain SQL — no Hibernate-generated DDL.
3. Include constraints, indexes, and foreign keys explicitly.
4. For new enums, use `CREATE TYPE ... AS ENUM (...)` at the top of the file.
5. Test by running the application (Flyway applies on startup).

Example:

```sql
-- V13__goal_manual_contribution.sql
CREATE TABLE goal_manual_contribution (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    goal_id BIGINT NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
    year_month VARCHAR(7) NOT NULL,
    amount NUMERIC(20, 2) NOT NULL,
    CONSTRAINT uk_goal_manual_contribution_goal_year UNIQUE (goal_id, year_month)
);
```

## Entities

### Base class: AuditableEntity

All entities with timestamps extend `AuditableEntity`:

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class AuditableEntity {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### Entity conventions

```java
@Entity
@Table(name = "account")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields use @Column with explicit name, nullable, length, precision
    @Column(nullable = false, length = 100)
    private String name;

    // PostgreSQL enums via @JdbcTypeCode + columnDefinition
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "account_type")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AccountType type;

    // Defaults via @Builder.Default
    @Column(nullable = false)
    @Builder.Default
    private boolean isManual = true;
}
```

### Key patterns

| Pattern | Convention |
|---------|-----------|
| Lombok | `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` on every entity |
| ID generation | `GenerationType.IDENTITY` (PostgreSQL sequences/serials) |
| Monetary values | `NUMERIC(20, 8)` in SQL, `BigDecimal` in Java |
| Timestamps | `TIMESTAMPTZ` in SQL, `Instant` in Java |
| Dates | `DATE` in SQL, `LocalDate` in Java |
| Enums | PostgreSQL native enums (`CREATE TYPE ... AS ENUM`) mapped via `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` |
| Defaults | `@Builder.Default` on fields, matching SQL `DEFAULT` in migration |
| Foreign keys | Always `ON DELETE CASCADE` where appropriate |
| Unique constraints | Named: `CONSTRAINT uk_{table}_{columns}` |
| Indexes | Named: `idx_{table}_{columns}`; partial indexes with `WHERE` clause for nullable columns |

## Connection

- **Database:** PostgreSQL 16
- **Connection pool:** HikariCP (max 10 connections, 30s timeout)
- **URL:** `jdbc:postgresql://localhost:5432/picsou` (overridden by `SPRING_DATASOURCE_URL`)

## Jackson serialization

```yaml
spring.jackson:
  write-dates-as-timestamps: false       # ISO-8601 strings
  default-property-inclusion: non_null   # omit null fields from JSON
```

## Don'ts

- **Never use `ddl-auto: create`, `update`, or `create-drop`** — Flyway owns every schema change.
- **Never use `GenerationType.AUTO` or `SEQUENCE`** — always `GenerationType.IDENTITY`.
- **Never use `Float` or `Double` for monetary values** — always `BigDecimal` / `NUMERIC(20, 8)`.
- **Never skip `ON DELETE CASCADE`** on child table FKs unless orphan rows are intentional.
- **Never use unnamed constraints** — always `CONSTRAINT uk_{table}_{columns}` / `idx_{table}_{columns}`.
- **Never edit a migration that has shipped** — Flyway checksums the whole file, comments
  included, and an edit fails validation at startup on every instance that already applied it.
  Corrections go in a new migration.
- **Never write a CHECK against `CURRENT_DATE` / `NOW()`** — PostgreSQL re-evaluates a table
  CHECK on every UPDATE of the row, whatever column changed, so such a constraint silently
  turns into "this row is frozen" once time moves past it (V2's `chk_goal_deadline`, dropped
  by V80). A rule about *new* values belongs in Bean Validation on the request DTO.
