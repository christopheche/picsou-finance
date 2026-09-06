package com.picsou.dto;

import java.time.LocalDate;
import java.util.Locale;

public enum TimeRange {
    _1D,
    _7D,
    _1M,
    _3M,
    YTD,
    _1Y,
    ALL;

    /** The first day the range covers. {@code ALL} reaches back further than any snapshot. */
    public LocalDate fromDate() {
        LocalDate today = LocalDate.now();
        return switch (this) {
            case _1D -> today.minusDays(1);
            case _7D -> today.minusDays(7);
            case _1M -> today.minusMonths(1);
            case _3M -> today.minusMonths(3);
            case YTD -> today.withDayOfYear(1);
            case _1Y -> today.minusYears(1);
            case ALL -> LocalDate.EPOCH;
        };
    }

    /**
     * Parses a wire value ({@code 1D}, {@code 7D}, {@code 1M}, {@code 3M}, {@code YTD},
     * {@code 1Y}, {@code ALL}), falling back to one year for anything unrecognised.
     *
     * <p>Only the numeric constants carry the leading underscore Java identifiers require.
     * Prefixing every value with it turned the two alphabetic ranges the UI actually sends —
     * {@code YTD} and {@code ALL} — into an {@code IllegalArgumentException} and silently
     * answered them with a one-year window.
     */
    public static TimeRange fromString(String value) {
        if (value == null) return _1Y;
        String name = value.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(name.isEmpty() || Character.isDigit(name.charAt(0)) ? "_" + name : name);
        } catch (IllegalArgumentException e) {
            return _1Y; // default
        }
    }
}
