package com.picsou.repository;

import com.picsou.model.FinarySession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinarySessionRepository extends JpaRepository<FinarySession, Long> {
    // memberId-scoped queries
    Optional<FinarySession> findByMemberId(Long memberId);
}
