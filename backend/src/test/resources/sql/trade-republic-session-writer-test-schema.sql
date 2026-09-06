-- Minimal H2 schema for TradeRepublicSessionWriterTest (@DataJpaTest).
--
-- Same rationale as ibkr-status-writer-test-schema.sql: the real migrations are
-- PostgreSQL-flavoured and cannot run on H2 (docs/conventions/testing.md), so this stands up
-- just the two tables the test touches. Both entities extend AuditableEntity, whose
-- created_at/updated_at are filled by Spring Data JPA auditing.

-- IF NOT EXISTS: @Sql runs before every test method against the same in-memory H2.
CREATE TABLE IF NOT EXISTS family_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    avatar_color VARCHAR(7)   NOT NULL,
    is_managed   BOOLEAN      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_republic_session (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT        NOT NULL REFERENCES family_member(id),
    session_token VARCHAR(2000) NOT NULL,
    refresh_token VARCHAR(4000),
    expires_at    TIMESTAMP,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
);
