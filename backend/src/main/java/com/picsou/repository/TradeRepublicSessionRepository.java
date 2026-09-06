package com.picsou.repository;

import com.picsou.model.TradeRepublicSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeRepublicSessionRepository extends JpaRepository<TradeRepublicSession, Long> {

    // memberId-scoped queries
    Optional<TradeRepublicSession> findByMemberId(Long memberId);
}
