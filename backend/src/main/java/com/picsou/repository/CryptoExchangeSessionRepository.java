package com.picsou.repository;

import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CryptoExchangeSessionRepository extends JpaRepository<CryptoExchangeSession, Long> {
    // memberId-scoped queries
    List<CryptoExchangeSession> findAllByMemberId(Long memberId);
    Optional<CryptoExchangeSession> findByIdAndMemberId(Long id, Long memberId);
    Optional<CryptoExchangeSession> findByExchangeTypeAndMemberId(ExchangeType exchangeType, Long memberId);
}
