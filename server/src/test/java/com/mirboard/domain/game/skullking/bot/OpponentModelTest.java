package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayerState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-230 — 상대 예측 어림: 카드 가치 표·예측 버릇·클램프. */
class OpponentModelTest {

    private static int code(SkullCard card) {
        return CardCode.of(card);
    }

    @Test
    void card_values_follow_the_ladder() {
        for (int n = 2; n <= 8; n++) {
            double skullKing = OpponentModel.value(n, CardCode.SKULL_KING);
            double pirate = OpponentModel.value(n, CardCode.PIRATE);
            double green14 = OpponentModel.value(n, code(SkullCard.of(SkullSuit.GREEN, 14)));
            double green1 = OpponentModel.value(n, code(SkullCard.of(SkullSuit.GREEN, 1)));
            // 스컬킹은 인어 2장에, 해적은 스컬킹 1장에 진다 — 리드 승률은 해적이 조금 높다.
            assertThat(Math.min(skullKing, pirate)).as("n=%d", n).isGreaterThan(green14);
            assertThat(green14).as("n=%d", n).isGreaterThan(green1);
            assertThat(OpponentModel.value(n, CardCode.ESCAPE)).as("n=%d 탈출 — 전원 탈출일 때만", n).isLessThan(0.1);
        }
    }

    @Test
    void habit_learns_a_seat_that_always_bids_zero() {
        // 4인 라운드 1~5 — 좌석 1 은 늘 0, 좌석 2 는 손패 장수만큼, 좌석 3 은 아직 기록 없음.
        List<List<Integer>> past = List.of(
                List.of(1, 0, 1, PlayerState.NO_BID),
                List.of(1, 0, 2, PlayerState.NO_BID),
                List.of(1, 0, 3, PlayerState.NO_BID),
                List.of(1, 0, 4, PlayerState.NO_BID),
                List.of(2, 0, 5, PlayerState.NO_BID));
        double[] habit = OpponentModel.habits(past, 4);

        assertThat(habit[1]).as("늘 0 — 음수 버릇").isLessThan(-0.5);
        assertThat(habit[2]).as("늘 장수만큼 — 양수 버릇").isGreaterThan(1.0);
        assertThat(habit[3]).as("기록 없음 — 0").isZero();

        int[] decent = {CardCode.PIRATE, code(SkullCard.of(SkullSuit.BLACK, 12)),
                code(SkullCard.of(SkullSuit.GREEN, 13)), code(SkullCard.of(SkullSuit.YELLOW, 4)),
                CardCode.ESCAPE, code(SkullCard.of(SkullSuit.PURPLE, 9))};
        int plain = OpponentModel.predictBid(4, decent, decent.length, 0, decent.length);
        assertThat(plain).as("버릇 없으면 손패대로").isPositive();
        assertThat(OpponentModel.predictBid(4, decent, decent.length, habit[1], decent.length))
                .as("늘 0 인 좌석은 같은 손패라도 더 낮게").isLessThan(plain);
        assertThat(OpponentModel.predictBid(4, decent, decent.length, 100, decent.length))
                .as("손패 장수로 클램프").isEqualTo(decent.length);
    }

    @Test
    void hold_estimates_shrink_with_more_players_after() {
        double one = OpponentModel.hold(1, CardCode.PIRATE);
        double seven = OpponentModel.hold(7, CardCode.PIRATE);
        assertThat(one).isGreaterThan(seven);
        assertThat(OpponentModel.hold(3, CardCode.ESCAPE)).isLessThan(0.05);
    }
}
