package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.withDeserted;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.rules.Ranking;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.score.Scorecard;
import com.mirboard.domain.game.yacht.score.Scoring;
import com.mirboard.domain.game.yacht.state.MatchResult.EndReason;
import com.mirboard.domain.game.yacht.state.TurnState;
import com.mirboard.domain.game.yacht.state.YachtState;
import com.mirboard.domain.game.yacht.state.YachtStateMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S3 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 판정 메서드({@code isFull} 등)가 프로퍼티로 새는
 * 회귀와 상태에 난수 관련 키가 생기는 회귀(D-182 — 미래 눈 없음)를 막는다.
 */
class YachtJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    private YachtState rich() {
        YachtState s = withTurn(openState(3, 4, 1), Dice.of(6, 6, 2, 3, 6), HoldMask.of(true, true, false, false, true), 2);
        s = YachtFixtures.withCard(s, 0, Scorecard.EMPTY.with(Category.SIXES, 24).with(Category.YACHT, 50)
                .with(Category.ONES, 2).with(Category.CHOICE, 0));
        return withDeserted(s, 2);
    }

    @Test
    void dice_and_holds_are_plain_arrays() throws Exception {
        assertThat(mapper.writeValueAsString(Dice.of(1, 2, 3, 4, 5))).isEqualTo("[1,2,3,4,5]");
        assertThat(mapper.writeValueAsString(HoldMask.of(true, false, false, true, false)))
                .isEqualTo("[true,false,false,true,false]");
        assertThat(roundTrip(Dice.of(6, 1, 6, 1, 6), Dice.class)).isEqualTo(Dice.of(6, 1, 6, 1, 6));
        assertThat(roundTrip(HoldMask.ALL, HoldMask.class)).isEqualTo(HoldMask.ALL);
    }

    @Test
    void an_open_and_a_finished_state_round_trip() throws Exception {
        YachtState open = rich();
        assertThat(roundTrip(open, YachtState.class)).isEqualTo(open);

        YachtState base = new YachtState(open.cards(), 12, -1, TurnState.none(), open.deserted(), 7, null);
        YachtState ended = new YachtState(base.cards(), 12, -1, base.turn(), base.deserted(), 7,
                Ranking.result(base, EndReason.NO_HUMANS));
        assertThat(roundTrip(ended, YachtState.class)).isEqualTo(ended);
    }

    @Test
    void the_state_has_no_random_or_derived_keys() throws Exception {
        JsonNode json = mapper.valueToTree(rich());
        List<String> keys = new ArrayList<>();
        collectKeys(json, keys);
        assertThat(keys).noneMatch(k -> k.toLowerCase().contains("seed") || k.toLowerCase().contains("random")
                || k.toLowerCase().contains("next"));
        assertThat(keys).doesNotContain("full", "private", "upperSubtotal", "bonus", "total");
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "cards", "round", "turnSeat", "turn", "deserted", "actionCount", "result");
    }

    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                keys.add(k);
                collectKeys(node.get(k), keys);
            }
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }

    @Test
    void actions_read_from_client_json() throws Exception {
        assertThat(mapper.readValue("{\"@action\":\"ROLL\"}", YachtAction.class)).isEqualTo(YachtAction.Roll.plain());
        assertThat(mapper.readValue("{\"@action\":\"ROLL\",\"held\":[true,false,false,false,true]}", YachtAction.class))
                .isEqualTo(new YachtAction.Roll(HoldMask.of(true, false, false, false, true)));
        assertThat(mapper.readValue("{\"@action\":\"HOLD\",\"held\":[false,false,true,false,false]}", YachtAction.class))
                .isEqualTo(new YachtAction.Hold(HoldMask.of(false, false, true, false, false)));
        assertThat(mapper.readValue("{\"@action\":\"SCORE\",\"category\":\"FULL_HOUSE\"}", YachtAction.class))
                .isEqualTo(new YachtAction.Score(Category.FULL_HOUSE));
    }

    @Test
    void malformed_actions_fail_to_deserialize() {
        assertThatThrownBy(() -> mapper.readValue("{\"@action\":\"HOLD\",\"held\":[true,false]}", YachtAction.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"@action\":\"SCORE\",\"category\":\"BOGUS\"}", YachtAction.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"@action\":\"EXPIRE\"}", YachtAction.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void events_carry_their_envelope_names_and_are_all_public() throws Exception {
        Dice d = Dice.of(2, 2, 3, 3, 3);
        List<YachtEvent> events = List.of(
                new YachtEvent.TurnChanged(1, 3, 45),
                new YachtEvent.DiceHeld(1, HoldMask.of(true, true, false, false, false)),
                new YachtEvent.DiceRolled(1, d, HoldMask.NONE, 1, Scoring.candidates(Scorecard.EMPTY, d)),
                new YachtEvent.Scored(1, Category.FULL_HOUSE, 13, 0, 0, 13, false),
                new YachtEvent.PlayerEliminated(2, 40),
                new YachtEvent.MatchEnded(EndReason.FINISHED, Ranking.placements(rich())));
        assertThat(events).extracting(YachtEvent::envelopeType).containsExactly(
                "TURN_CHANGED", "DICE_HELD", "DICE_ROLLED", "SCORED", "PLAYER_ELIMINATED", "MATCH_ENDED");
        for (YachtEvent e : events) {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
            JsonNode json = mapper.valueToTree(e);
            assertThat(json.get("@event").asText()).isEqualTo(e.envelopeType());
            assertThat(json.has("private")).isFalse();
            assertThat(mapper.treeToValue(json, YachtEvent.class)).isEqualTo(e);
        }
    }

    @Test
    void the_public_view_serializes_with_computed_totals_and_candidates() throws Exception {
        JsonNode view = mapper.valueToTree(YachtStateMapper.toPublicView(rich(), 12_000L));
        assertThat(view.get("phase").asText()).isEqualTo("PLAYING");
        assertThat(view.get("dice").toString()).isEqualTo("[6,6,2,3,6]");
        assertThat(view.get("seats").get(0).get("bonus").asInt()).isZero();
        assertThat(view.get("seats").get(0).get("total").asInt()).isEqualTo(76);
        assertThat(view.get("seats").get(2).get("deserted").asBoolean()).isTrue();
        assertThat(view.get("candidates").has("SIXES")).isTrue();
        assertThat(view.get("turnRemainingMs").asLong()).isEqualTo(12_000);
        assertThat(view.has("actionCount")).isFalse();

        JsonNode fresh = mapper.valueToTree(YachtStateMapper.toPublicView(openState(2, 1, 0), null));
        assertThat(fresh.get("dice").isNull()).isTrue();
        assertThat(fresh.get("candidates").isEmpty()).isTrue();
        assertThat(fresh.get("turnRemainingMs").isNull()).isTrue();
        assertThat(YachtStateMapper.toPublicView(openState(2, 1, 0), -50L).turnRemainingMs()).isZero();
    }
}
