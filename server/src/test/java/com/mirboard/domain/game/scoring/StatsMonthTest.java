package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-209 — 이번 달 전적의 달 경계는 Asia/Seoul 매달 1일 0시다(서버 시계는 UTC). */
class StatsMonthTest {

    private static Clock at(String utcInstant) {
        return Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
    }

    @Test
    void the_month_rolls_over_at_kst_midnight_not_utc_midnight() {
        // KST 10월 31일 23:59:59 = UTC 14:59:59 — 아직 10월.
        assertThat(StatsMonth.current(at("2026-10-31T14:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 1));
        // KST 11월 1일 00:00:00 = UTC 10월 31일 15:00:00 — UTC 로는 아직 10월이지만 11월이다.
        assertThat(StatsMonth.current(at("2026-10-31T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(StatsMonth.current(at("2026-11-12T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 1));
        // 해가 바뀔 때도.
        assertThat(StatsMonth.current(at("2026-12-31T15:00:00Z"))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void label_is_year_dash_month() {
        assertThat(StatsMonth.label(LocalDate.of(2026, 11, 1))).isEqualTo("2026-11");
        assertThat(StatsMonth.label(LocalDate.of(2027, 1, 1))).isEqualTo("2027-01");
    }

    /** 누적 전적을 쓰는 한 곳({@code record}·{@code recordDraw})이 주입된 Clock 의 달에 게임 행과 전체 행을 같이 쌓는다. */
    @Test
    void recording_writes_the_game_row_and_the_all_games_row_of_the_clocks_kst_month() {
        UserGameStatsRepository repo = mock(UserGameStatsRepository.class);
        UserMonthlyStatsRepository monthly = mock(UserMonthlyStatsRepository.class);
        UserGameStatsService service = new UserGameStatsService(repo, monthly, mock(UserRepository.class),
                new HonoraryTierPolicy(List.of()), at("2026-10-31T15:00:00Z"));
        LocalDate november = LocalDate.of(2026, 11, 1);

        service.record(7L, "ONE_CARD", true, 1016, false);
        verify(repo).record(7L, "ONE_CARD", 1016, 1, 0, 0);
        verify(monthly).record(7L, "ONE_CARD", november, 1, 0, 0, "W");
        verify(monthly).record(7L, UserMonthlyStats.ALL_GAMES, november, 1, 0, 0, "W");

        service.record(7L, "ONE_CARD", false, null, true);
        verify(monthly).record(7L, "ONE_CARD", november, 0, 1, 1, "L");
        verify(monthly).record(7L, UserMonthlyStats.ALL_GAMES, november, 0, 1, 1, "L");

        service.recordDraw(7L, "OMOK");
        verify(monthly).record(7L, "OMOK", november, 0, 0, 0, "D");
        verify(monthly).record(7L, UserMonthlyStats.ALL_GAMES, november, 0, 0, 0, "D");
    }
}
