package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Chain;
import com.picsou.service.UserContext;
import com.picsou.service.WalletSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/wallet")
public class WalletController {

    private final WalletSyncService walletService;
    private final UserContext userContext;

    public WalletController(WalletSyncService walletService, UserContext userContext) {
        this.walletService = walletService;
        this.userContext = userContext;
    }

    @PostMapping
    public AccountResponse addWallet(@Valid @RequestBody AddWalletRequest req) {
        return walletService.addWallet(req.chain(), req.address(), req.label(), userContext.currentMemberId());
    }

    @PostMapping("/{id}/sync")
    public AccountResponse sync(@PathVariable Long id) {
        return walletService.sync(id, userContext.currentMemberId());
    }

    @GetMapping
    public List<WalletSyncService.WalletStatusResponse> listWallets() {
        return walletService.listWallets(userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeWallet(@PathVariable Long id) {
        walletService.removeWallet(id, userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code address} is bounded and format-checked by {@code WalletSyncService.addWallet},
     * which returns a 400 naming the problem — bean validation cannot reach the per-chain rule.
     * {@code label} has no such guard, so it is bounded here to the width of
     * {@code wallet_address.label} ({@code varchar(100)}); without it a long label reaches the
     * insert and comes back as a 500, rolling back the on-chain sync that already ran.
     */
    record AddWalletRequest(
        @NotNull Chain chain,
        String address,
        @Size(max = 100) String label
    ) {}
}
