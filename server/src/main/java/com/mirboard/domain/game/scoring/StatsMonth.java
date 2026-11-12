package com.mirboard.domain.game.scoring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * D-209 — 이번 달 전적의 "달". 경계는 서버 시간대(UTC)가 아니라 <b>Asia/Seoul 의 매달 1일 0시</b>다. 저장 값은 그 달의 1일.
 */
public final class StatsMonth {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private StatsMonth() {
    }

    /** 지금이 속한 달의 1일(KST). */
    public static LocalDate current(Clock clock) {
        return clock.instant().atZone(ZONE).toLocalDate().withDayOfMonth(1);
    }

    /** 응답용 "YYYY-MM". */
    public static String label(LocalDate month) {
        return YearMonth.from(month).toString();
    }
}
