package com.picsou.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
    BigDecimal totalNetWorth,
    BigDecimal totalLiabilities,
    List<NetWorthPoint> netWorthHistory,
    List<DistributionItem> distribution,
    List<DistributionItem> liabilities,
    List<GoalProgressResponse> goalSummaries
) {
    public record AccountPoint(BigDecimal total, BigDecimal invested, BigDecimal pnl) {}

    public record NetWorthPoint(
        LocalDate date,
        BigDecimal total,
        BigDecimal invested,
        BigDecimal pnl,
        Map<Long, AccountPoint> accounts
    ) {
        public NetWorthPoint(LocalDate date, BigDecimal total, BigDecimal invested, BigDecimal pnl) {
            this(date, total, invested, pnl, null);
        }
    }

    public record DistributionItem(
        Long accountId,
        String name,
        String color,
        BigDecimal balanceEur,
        double percentage,
        String accountType,
        boolean hasHoldings
    ) {}

    /**
     * One hourly point of the 24H series.
     *
     * <p>{@code timestamp} is an {@link Instant}, not a {@code LocalDateTime}: the series merges
     * Yahoo and CoinGecko bars, so the whole intraday pipeline works in UTC (see
     * {@code HistoryService.INTRADAY_ZONE}) and Jackson serialises it with its {@code Z}, which
     * is what lets the browser render the point at the viewer's own wall-clock time. A zone-less
     * timestamp was read as browser-local and labelled the day's chart with the wrong hours.
     */
    public record NetWorthIntradayPoint(
        Instant timestamp,
        BigDecimal total,
        BigDecimal invested
    ) {}
}
