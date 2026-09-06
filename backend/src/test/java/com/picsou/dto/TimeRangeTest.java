package com.picsou.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TimeRangeTest {

    @Test
    void fromString_parsesEveryRangeTheDashboardSends() {
        assertThat(TimeRange.fromString("1D")).isEqualTo(TimeRange._1D);
        assertThat(TimeRange.fromString("7D")).isEqualTo(TimeRange._7D);
        assertThat(TimeRange.fromString("1M")).isEqualTo(TimeRange._1M);
        assertThat(TimeRange.fromString("3M")).isEqualTo(TimeRange._3M);
        assertThat(TimeRange.fromString("1Y")).isEqualTo(TimeRange._1Y);
    }

    @Test
    void fromString_parsesTheAlphabeticRanges_whichUsedToFallBackToOneYear() {
        // "_YTD" and "_ALL" are not enum constants, so prefixing every value with the underscore
        // the numeric ones need threw and silently answered both with a one-year window.
        assertThat(TimeRange.fromString("YTD")).isEqualTo(TimeRange.YTD);
        assertThat(TimeRange.fromString("ALL")).isEqualTo(TimeRange.ALL);
    }

    @Test
    void fromString_fallsBackToOneYear_forNullAndUnknownValues() {
        assertThat(TimeRange.fromString(null)).isEqualTo(TimeRange._1Y);
        assertThat(TimeRange.fromString("")).isEqualTo(TimeRange._1Y);
        assertThat(TimeRange.fromString("42Q")).isEqualTo(TimeRange._1Y);
    }

    @Test
    void fromDate_ytdStartsOnJanuaryFirst_andAllReachesBeforeAnySnapshot() {
        LocalDate today = LocalDate.now();

        assertThat(TimeRange.YTD.fromDate()).isEqualTo(today.withDayOfYear(1));
        assertThat(TimeRange._3M.fromDate()).isEqualTo(today.minusMonths(3));
        assertThat(TimeRange.ALL.fromDate()).isEqualTo(LocalDate.EPOCH);
    }
}
