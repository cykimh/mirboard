package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.RaceView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.SeatView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D-128 — 공개·비공개 뷰와 State Hiding 경계(손패·뽑을 더미 순서·봇 반응 시각). */
class OneCardStateMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OPENED_AT = 10_000L;

    private static OneCardState threeSeats() {
        return seats(hand(heart(9), club(3), club(4)), hand(spade(4), spade(6)), hand(diamond(4)))
                .top(heart(5)).drawPile(diamond(9), diamond(10)).turn(1).build();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void the_table_view_shows_hand_counts_and_the_pile_count_but_no_cards() throws Exception {
        OneCardState state = threeSeats();

        TableView view = OneCardStateMapper.toTableView(state, OPENED_AT);

        assertThat(view.seats()).containsExactly(
                new SeatView(0, 3, null), new SeatView(1, 2, null), new SeatView(2, 1, null));
        assertThat(view.drawPileCount()).isEqualTo(2);
        assertThat(view.topCard()).isEqualTo(heart(5));
        assertThat(view.turnSeat()).isEqualTo(1);
        assertThat(view.phase()).isEqualTo("PLAYING");
        assertThat(view.race()).isNull();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(view));
        assertThat(fieldNames(json)).doesNotContain("hands", "hand", "drawPile", "discardPile");
        assertThat(fieldNames(json.get("seats").get(0))).containsExactlyInAnyOrder("seat", "handCount", "eliminated");
    }

    @Test
    void an_open_race_exposes_only_the_public_window_and_the_time_left_to_its_end() throws Exception {
        RaceWindow race = new RaceWindow(7, 0, 3, -40, 25, OPENED_AT, 3_000, 1,
                new RaceWindow.BotPress(2, false, 1_200));
        OneCardState state = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                .top(heart(5)).race(race).build();

        TableView view = OneCardStateMapper.toTableView(state, OPENED_AT + 1_000);

        assertThat(view.phase()).isEqualTo("RACE");
        assertThat(view.race()).isEqualTo(new RaceView(7, 0, 3, -40, 25, 3_000, 2_000));
        JsonNode raceJson = JSON.readTree(JSON.writeValueAsString(view)).get("race");
        assertThat(fieldNames(raceJson)).containsExactlyInAnyOrder(
                "raceId", "ownerSeat", "slot", "jitterX", "jitterY", "windowMillis", "remainingMillis");
    }

    @Test
    void the_time_left_never_goes_below_zero() {
        RaceWindow race = new RaceWindow(7, 0, 3, 0, 0, OPENED_AT, 3_000, 1, null);
        OneCardState state = seats(hand(heart(9)), hand(spade(4), spade(6)))
                .top(heart(5)).race(race).build();

        assertThat(OneCardStateMapper.toTableView(state, OPENED_AT + 9_000).race().remainingMillis()).isZero();
    }

    @Test
    void eliminated_seats_show_the_reason() {
        OneCardState state = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.DESERTED, 3).top(heart(5)).turn(0).build();

        assertThat(OneCardStateMapper.toTableView(state, 0).seats().get(1))
                .isEqualTo(new SeatView(1, 0, Elimination.Reason.DESERTED));
    }

    @Test
    void the_private_view_carries_the_seats_whole_hand_and_the_state_version() {
        OneCardState state = threeSeats();

        PrivateView view = OneCardStateMapper.toPrivateView(state, 0);

        assertThat(view).isEqualTo(new PrivateView(0, List.of(heart(9), club(3), club(4)), state.version()));
        assertThatThrownBy(() -> OneCardStateMapper.toPrivateView(state, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
