package com.picsou.service;

import com.picsou.repository.TradeRepublicSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists Trade Republic session changes that must outlive a failed sync, each in its own
 * transaction.
 *
 * <p>{@link TradeRepublicSyncService} is {@code @Transactional} and rethrows
 * {@code SyncException} to the controller, which marks that transaction rollback-only. Two
 * writes it makes on the way out were silently discarded with it on the manual path:
 *
 * <ul>
 *   <li>the <b>delete</b> of a session whose refresh Trade Republic rejected — the row
 *       survived, {@code getSessionStatus} kept answering {@code active=true}, and every click
 *       on Sync repeated the sidecar round-trip and the same 422 instead of offering the
 *       re-authentication form;</li>
 *   <li>the <b>rotated tokens</b> of a successful refresh followed by a transient failure on
 *       the retry (a WebSocket timeout, say) — the old refresh token was restored, TR had already
 *       invalidated it, and the next sync cleared the session for good, forcing a full re-auth
 *       over a timeout.</li>
 * </ul>
 *
 * <p>The scheduled path ({@code resyncIfSessionActive}) catches inside the transaction and so
 * committed both — the two paths disagreed. This bean's {@code REQUIRES_NEW} methods commit
 * independently of the caller's transaction, like {@link IbkrStatusWriter} and
 * {@link DegiroSessionStatusWriter}; it must be a separate Spring bean, since a
 * {@code REQUIRES_NEW} method invoked via {@code this} would not cross the proxy.
 *
 * <p>Safe to call from inside the sync transaction, unlike {@link CryptoExchangeStatusWriter}:
 * that transaction only ever <em>reads</em> the session row (the service no longer mutates the
 * managed entity), so it holds no row lock for the new transaction to wait on. Keep it that way —
 * a {@code save} of the managed session before one of these calls would recreate the hang
 * documented on the exchange writer.
 */
@Service
public class TradeRepublicSessionWriter {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicSessionWriter.class);

    private final TradeRepublicSessionRepository sessionRepository;

    public TradeRepublicSessionWriter(TradeRepublicSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Stores the tokens a successful refresh returned. {@code encryptedRefreshToken} is null when
     * TR did not rotate the refresh token, in which case the stored one is kept.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeRefreshedTokens(Long memberId, String encryptedSessionToken,
                                     String encryptedRefreshToken, Instant expiresAt) {
        sessionRepository.findByMemberId(memberId).ifPresentOrElse(session -> {
            session.setSessionToken(encryptedSessionToken);
            if (encryptedRefreshToken != null) {
                session.setRefreshToken(encryptedRefreshToken);
            }
            session.setExpiresAt(expiresAt);
            sessionRepository.save(session);
        }, () -> log.warn("No Trade Republic session for member {} to store refreshed tokens on "
            + "(cleared mid-sync?) — the next sync will have to re-authenticate", memberId));
    }

    /** Deletes the member's session: Trade Republic rejected it and only a re-auth can follow. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
    }
}
