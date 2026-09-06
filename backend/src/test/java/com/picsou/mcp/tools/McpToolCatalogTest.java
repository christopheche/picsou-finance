package com.picsou.mcp.tools;

import com.picsou.config.McpToolConfig;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.AccountConnectionService;
import com.picsou.service.AccountService;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.DashboardService;
import com.picsou.service.FamilyViewService;
import com.picsou.service.GoalService;
import com.picsou.service.HistoryService;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.PriceService;
import com.picsou.service.SyncService;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import com.picsou.service.WalletSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The curation guard. The MCP surface is intentionally a small, audited allowlist of safe,
 * member-scoped operations. These tests fail loudly the moment that boundary changes — whether a new
 * tool appears, a tool name starts to look like an auth/credential/admin/export operation, or a tool
 * is left without a scope. They build the provider through the real {@link McpToolConfig} bean method
 * so the test and production wiring can never drift.
 */
class McpToolCatalogTest {

    /**
     * The exact, curated set of MCP tools Picsou exposes. Changing the surface is a deliberate act:
     * it must be reflected here, and a reviewer sees precisely what was added or removed.
     */
    private static final Set<String> EXPECTED_TOOLS = Set.of(
        // accounts:read / accounts:write
        "list_accounts", "get_account", "get_account_holdings", "get_account_balance_history",
        "create_manual_account", "update_account", "delete_account", "add_balance_snapshot",
        "upsert_holding", "delete_holding",
        // transactions:read / transactions:write
        "list_account_transactions", "add_transaction", "update_transaction", "delete_transaction",
        // goals:read / goals:write
        "list_goals", "get_goal", "get_goal_monthly_entries", "create_goal", "update_goal",
        "delete_goal", "set_goal_month_contribution",
        // dashboard:read / family:read / prices:read (read-only insights)
        "get_dashboard", "get_net_worth_history", "get_profit_and_loss", "get_family_dashboard", "get_price",
        // sync:trigger (refresh existing connections only)
        "trigger_bank_sync", "trigger_broker_sync", "trigger_crypto_exchange_sync", "trigger_crypto_wallet_sync"
    );

    private static final List<Class<?>> TOOL_CLASSES = List.of(
        AccountTools.class, TransactionTools.class, GoalTools.class, InsightTools.class, SyncTools.class);

    /** Build the provider exactly as production does, with mocked services (never invoked during catalog build). */
    private ToolCallbackProvider buildProvider() {
        AccountTools account = new AccountTools(
            mock(AccountService.class), mock(AccountConnectionService.class), mock(UserContext.class));
        TransactionTools tx = new TransactionTools(
            mock(AccountService.class), mock(ManualTransactionService.class), mock(UserContext.class));
        GoalTools goal = new GoalTools(mock(GoalService.class), mock(UserContext.class));
        InsightTools insight = new InsightTools(
            mock(DashboardService.class), mock(HistoryService.class), mock(PriceService.class),
            mock(FamilyViewService.class), mock(AccountService.class), mock(UserContext.class));
        SyncTools sync = new SyncTools(
            mock(SyncService.class), mock(TradeRepublicSyncService.class), mock(BoursoSyncService.class),
            mock(CryptoExchangeSyncService.class), mock(WalletSyncService.class), mock(UserContext.class));
        return new McpToolConfig().picsouMcpTools(account, tx, goal, insight, sync);
    }

    private Set<String> registeredToolNames() {
        return Arrays.stream(buildProvider().getToolCallbacks())
            .map(c -> c.getToolDefinition().name())
            .collect(Collectors.toSet());
    }

    @Test
    void registeredToolSet_isExactlyTheCuratedAllowlist() {
        assertThat(registeredToolNames()).isEqualTo(EXPECTED_TOOLS);
    }

    @Test
    void noToolNameLooksLikeAnAuthCredentialAdminOrExportOperation() {
        // Defence in depth: even if someone updates EXPECTED_TOOLS, a dangerous name still fails here.
        List<String> forbidden = List.of(
            "auth", "credential", "login", "logout", "password", "passcode", "mfa", "totp",
            "admin", "export", "setup", "recovery", "token", "secret", "initiate", "complete",
            "wizard", "powens", "finary", "add_exchange", "add_wallet", "delete_member", "create_member");
        for (String name : registeredToolNames()) {
            for (String bad : forbidden) {
                assertThat(name)
                    .as("tool name '%s' must not look like a forbidden operation ('%s')", name, bad)
                    .doesNotContain(bad);
            }
        }
    }

    @Test
    void everyToolMethodIsGatedByAScopeInTheAllowlist() {
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method m : toolClass.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                RequiresScope rs = m.getAnnotation(RequiresScope.class);
                assertThat(rs)
                    .as("tool method %s.%s must carry @RequiresScope", toolClass.getSimpleName(), m.getName())
                    .isNotNull();
                assertThat(Scopes.ALL)
                    .as("@RequiresScope on %s.%s must use a scope in Scopes.ALL", toolClass.getSimpleName(), m.getName())
                    .contains(rs.value());
            }
        }
    }
}
