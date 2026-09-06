package com.picsou.repository;

import com.picsou.model.WalletAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletAddressRepository extends JpaRepository<WalletAddress, Long> {
    // memberId-scoped queries
    List<WalletAddress> findAllByMemberId(Long memberId);
    Optional<WalletAddress> findByIdAndMemberId(Long id, Long memberId);
}
