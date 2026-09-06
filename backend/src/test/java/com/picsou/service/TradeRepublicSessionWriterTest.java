package com.picsou.service;

import com.picsou.model.FamilyMember;
import com.picsou.model.TradeRepublicSession;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one property {@link TradeRepublicSessionWriter} exists for: its writes commit in
 * their own REQUIRES_NEW transaction and survive the caller's rollback.
 *
 * <p>{@code TradeRepublicSyncService.sync} clears the session (or stores rotated tokens) and
 * then rethrows {@code SyncException}, which marks the surrounding {@code @Transactional} call
 * rollback-only. A Mockito test can only verify that the writer was <em>invoked</em>
 * ({@code TradeRepublicSyncServiceTest}); this slice test proves the row is actually gone —
 * or actually carries the new tokens — once the caller has rolled back, the exact behaviour
 * the previous in-transaction delete/save got wrong. Same shape as {@code IbkrStatusWriterTest}.
 */
@DataJpaTest
@Import(TradeRepublicSessionWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/trade-republic-session-writer-test-schema.sql")
class TradeRepublicSessionWriterTest {

    @Autowired TradeRepublicSessionWriter sessionWriter;
    @Autowired TradeRepublicSessionRepository sessionRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void clear_deletesTheSessionEvenWhenTheCallingTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long memberId = seedSession(tx);

        // Caller transaction: the shape of a rejected refresh — clear, then rethrow.
        assertThatThrownBy(() -> tx.execute(status -> {
            sessionWriter.clear(memberId);
            throw new IllegalStateException("simulated SyncException after clear");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(sessionRepository.findByMemberId(memberId)).isEmpty();
    }

    @Test
    void storeRefreshedTokens_persistsEvenWhenTheCallingTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long memberId = seedSession(tx);
        Instant expiresAt = Instant.now().plusSeconds(7200);

        // Caller transaction: a refresh that succeeded, followed by a retry that failed.
        assertThatThrownBy(() -> tx.execute(status -> {
            sessionWriter.storeRefreshedTokens(memberId, "enc:new-session", "enc:new-refresh", expiresAt);
            throw new IllegalStateException("simulated transient failure on the retry");
        })).isInstanceOf(IllegalStateException.class);

        TradeRepublicSession stored = sessionRepository.findByMemberId(memberId).orElseThrow();
        assertThat(stored.getSessionToken()).isEqualTo("enc:new-session");
        assertThat(stored.getRefreshToken()).isEqualTo("enc:new-refresh");
    }

    @Test
    void storeRefreshedTokens_keepsTheStoredRefreshTokenWhenTrDidNotRotateIt() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long memberId = seedSession(tx);

        tx.execute(status -> {
            sessionWriter.storeRefreshedTokens(memberId, "enc:new-session", null, Instant.now());
            return null;
        });

        TradeRepublicSession stored = sessionRepository.findByMemberId(memberId).orElseThrow();
        assertThat(stored.getSessionToken()).isEqualTo("enc:new-session");
        assertThat(stored.getRefreshToken()).isEqualTo("enc:old-refresh");
    }

    private Long seedSession(TransactionTemplate tx) {
        Long memberId = tx.execute(status -> {
            FamilyMember member = familyMemberRepository.save(
                FamilyMember.builder().displayName("Owner").build());
            sessionRepository.save(TradeRepublicSession.builder()
                .member(member)
                .sessionToken("enc:old-session")
                .refreshToken("enc:old-refresh")
                .expiresAt(Instant.now())
                .build());
            return member.getId();
        });
        assertThat(memberId).isNotNull();
        return memberId;
    }
}
