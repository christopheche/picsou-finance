-- V81: account.deleted_at becomes TIMESTAMPTZ, like every other Instant column.
--
-- V30 declared it TIMESTAMP (no time zone) while Account maps it to java.time.Instant, and
-- docs/conventions/database.md prescribes TIMESTAMPTZ for exactly that pairing. Hibernate's
-- schema validation cannot catch the mismatch: pgjdbc reports both types as Types.TIMESTAMP.
--
-- Nothing depends on the value yet -- the column is read only as a null/non-null flag
-- (@SQLRestriction on Account, the two JPQL predicates and the native EXISTS in
-- AccountRepository) -- which is what makes this the cheap moment to fix it, before the first
-- feature that displays or compares a deletion time inherits the host's UTC offset.
--
-- USING ... AT TIME ZONE 'UTC' is safe because Hibernate is the only writer (AccountService.delete,
-- CryptoExchangeSyncService and WalletSyncService all go through setDeletedAt(Instant.now())) and
-- it binds Instant through a UTC calendar, so the wall-clock values already stored are UTC.
ALTER TABLE account
    ALTER COLUMN deleted_at TYPE TIMESTAMPTZ USING deleted_at AT TIME ZONE 'UTC';
