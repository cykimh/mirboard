package com.mirboard.domain.game.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-299 — 판의 ELO 배율: 봇이 끼면 1/4, 그 밖은 1. 게스트는 배율을 바꾸지 않는다(덧붙임). */
class RatedMatchPolicyTest {

    private static final long HUMAN_A = 1;
    private static final long HUMAN_B = 2;
    private static final long BOT = 90;
    /** 게스트도 정책에게는 봇 아닌 사람일 뿐이다 — 레이팅을 저장하지 않는 것은 UserGameStatsService 의 일. */
    private static final long GUEST = 50;

    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final RatedMatchPolicy policy = new RatedMatchPolicy(bots);

    RatedMatchPolicyTest() {
        when(bots.isBot(BOT)).thenReturn(true);
    }

    @Test
    void members_only_match_is_fully_rated() {
        assertThat(policy.eloFactor(List.of(HUMAN_A, HUMAN_B))).isEqualTo(1.0);
    }

    @Test
    void a_match_with_a_bot_is_rated_at_a_quarter() {
        assertThat(policy.eloFactor(List.of(HUMAN_A, BOT)))
                .isEqualTo(EloCalculator.BOT_MATCH_FACTOR).isEqualTo(0.25);
    }

    @Test
    void a_match_with_a_guest_is_fully_rated() {
        assertThat(policy.eloFactor(List.of(HUMAN_A, GUEST))).isEqualTo(1.0);
    }

    @Test
    void a_bot_and_a_guest_together_follow_the_bot_rule() {
        assertThat(policy.eloFactor(List.of(HUMAN_A, GUEST, BOT))).isEqualTo(EloCalculator.BOT_MATCH_FACTOR);
    }

    @Test
    void no_match_is_unrated_any_more() {
        assertThat(policy.eloFactor(List.of(GUEST, BOT))).isGreaterThan(RatedMatchPolicy.UNRATED);
    }
}
