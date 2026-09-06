package com.picsou.controller;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.PnlResponse;
import com.picsou.service.HistoryService;
import com.picsou.service.UserContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final UserContext userContext;

    public HistoryController(HistoryService historyService, UserContext userContext) {
        this.historyService = historyService;
        this.userContext = userContext;
    }

    /**
     * {@code months} is bounded here rather than in the service: it feeds
     * {@code LocalDate.now().minusMonths(months)}, which throws {@code DateTimeException} —
     * unhandled, so a 500 — once the result leaves the supported year range, while a negative
     * value silently inverts the range and returns an empty series with a 200.
     * {@link #MAX_MONTHS} is 50 years, far past any real history.
     */
    @GetMapping
    public List<DashboardResponse.NetWorthPoint> getHistory(
        @RequestParam List<Long> accountIds,
        @RequestParam(defaultValue = "12") int months,
        @RequestParam(defaultValue = "false") boolean split
    ) {
        requireMonthsInRange(months);
        return historyService.buildHistory(accountIds, months, split, userContext.currentMemberId());
    }

    @GetMapping("/pnl")
    public PnlResponse getPnl(
        @RequestParam List<Long> accountIds,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from
    ) {
        return historyService.buildPnl(accountIds, userContext.currentMemberId(), from);
    }

    @GetMapping("/net-worth/intraday")
    public List<DashboardResponse.NetWorthIntradayPoint> getIntraday(@RequestParam List<Long> accountIds) {
        return historyService.buildIntradayHistory(accountIds, userContext.currentMemberId());
    }

    /** Upper bound on the {@code months} window: 50 years, far past any real history. */
    static final int MAX_MONTHS = 600;

    private static void requireMonthsInRange(int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("months must be between 1 and " + MAX_MONTHS);
        }
    }
}
