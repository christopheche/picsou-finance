-- V83: drop the shared_resource rows V77 left pointing at accounts it merged away.
--
-- V77 re-pointed the nine child tables that reference account, but shared_resource (V20) holds an
-- untyped (resource_type, resource_id) pair with no foreign key: nothing cascaded and nothing was
-- moved, so a MANUAL share the owner had put on a duplicate that lost the merge is now a row
-- addressing an id that no longer exists.
--
-- Deleted rather than re-pointed: V77's account_merge_map was a TEMP TABLE ... ON COMMIT DROP, so
-- which survivor a given loser folded into is no longer knowable. The row already has no effect --
-- FamilyViewService resolves resource_id against account and finds nothing -- so removing it
-- changes no behaviour; the owner re-shares the surviving account to restore the access the merge
-- silently took away.
--
-- Scoped to ACCOUNT: V77 never touched goals, and a GOAL share is none of this migration's business.
DELETE FROM shared_resource s
 WHERE s.resource_type = 'ACCOUNT'
   AND NOT EXISTS (SELECT 1 FROM account a WHERE a.id = s.resource_id);
