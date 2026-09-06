-- V82: at most one live synced account per (member_id, external_account_id, provider).
--
-- Every connector upserts through AccountRepository.findByExternalAccountIdAndMemberId, an
-- Optional query, and nothing in the schema enforced the key it assumes -- V6's
-- idx_account_external_id is not unique. Two syncs of one member running at once (the 03:00
-- SchedulerService pass and a user-triggered POST /api/sync/...) both read empty and both insert;
-- from then on the Optional query raises IncorrectResultSizeDataAccessException and every later
-- sync of that member fails until a row is deleted by hand. V77 repaired the rows an earlier
-- instance of this shape produced but added no constraint.
--
-- The key is the one V77 merged on, provider included, and not (member_id, external_account_id):
-- an Enable Banking external id is the bank's own opaque string, so two institutions may
-- legitimately hand out the same one to one member. V77 keeps such a pair apart on purpose (see
-- its header and DuplicateSyncAccountMergeMigrationTest.neverMergesTwoBanksThatShareAnOpaqueAccountId),
-- so a unique index on the two columns alone would fail to build on a database that has one.
--
-- Partial: external_account_id is free text on manual accounts (V75), and a soft-deleted row is
-- deliberately allowed to share the key with the live account that replaced it.
--
-- NULLS NOT DISTINCT (PostgreSQL 15+; the project ships 16) so a NULL provider behaves as V77's
-- GROUP BY did -- two rows with no provider are one key, not two.
--
-- Guarded rather than unconditional: V77 left no duplicate on this key, but nothing prevented the
-- race from creating a new one between V77 and this migration, and a migration that fails is an
-- instance that will not boot. Where duplicates remain the index is skipped with a warning naming
-- them, and behaviour is exactly what it was before this file.
DO $$
DECLARE
    duplicate_groups BIGINT;
BEGIN
    SELECT count(*) INTO duplicate_groups
      FROM (SELECT 1
              FROM account
             WHERE external_account_id IS NOT NULL
               AND is_manual = false
               AND deleted_at IS NULL
             GROUP BY member_id, external_account_id, provider
            HAVING count(*) > 1) still_duplicated;

    IF duplicate_groups = 0 THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uk_account_member_external_provider
            ON account (member_id, external_account_id, provider) NULLS NOT DISTINCT
         WHERE external_account_id IS NOT NULL
           AND is_manual = false
           AND deleted_at IS NULL;
    ELSE
        RAISE WARNING 'V82: % live synced account group(s) still share (member_id, external_account_id, provider); uk_account_member_external_provider was not created. Merge them, then create the index by hand.', duplicate_groups;
    END IF;
END $$;
