package com.mirboard.domain.game.dambaduk;

import static com.mirboard.domain.game.dambaduk.DambadukStates.ENGINE;
import static com.mirboard.domain.game.dambaduk.DambadukStates.move;
import static com.mirboard.domain.game.dambaduk.board.Dir.LEFT;
import static com.mirboard.domain.game.dambaduk.board.Dir.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * S2 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 파생 메서드({@code isOver}·{@code wallSet} 등)가 프로퍼티로
 * 새거나, 서버 전용 {@code TimeoutAct} 를 클라가 만들 수 있게 되는 회귀를 막는다.
 */
class DambadukJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void playing_and_ended_states_round_trip_and_store_only_their_components() throws Exception {
        DambadukState s = ENGINE.apply(DambadukStates.threePlaying(), 0, move(0, LEFT, UP)).newState();
        String json = mapper.writeValueAsString(s);
        assertThat(mapper.readValue(json, DambadukState.class)).isEqualTo(s);
        assertThat(keys(mapper.readTree(json))).containsExactly(
                "exits", "firstSeat", "jumpCount", "jumpsLeft", "moveCount", "nextStoneId", "phase", "placementQueue",
                "result", "seatCount", "stones", "timeouts", "turnSeat", "walls");
        DambadukState ended = ENGINE.desert(DambadukStates.twoPlaying(), 0).newState();
        String endedJson = mapper.writeValueAsString(ended);
        assertThat(mapper.readValue(endedJson, DambadukState.class)).isEqualTo(ended);
        assertThat(keys(mapper.readTree(endedJson).get("result"))).containsExactly(
                "causeSeat", "draw", "reason", "standings", "winners");
        assertThat(keys(mapper.readTree(json).get("stones").get(0))).containsExactly("id", "kind", "seat", "x", "y");
    }

    @Test
    void a_state_without_optional_lists_gets_defaults() throws Exception {
        String json = "{\"seatCount\":2,\"firstSeat\":0,\"phase\":\"PLACING\",\"stones\":[],\"walls\":[],"
                + "\"placementQueue\":[0,1,1,0],\"turnSeat\":0,\"nextStoneId\":0,\"moveCount\":0,\"jumpCount\":0}";
        DambadukState s = mapper.readValue(json, DambadukState.class);
        assertThat(s.timeouts()).containsExactly(0, 0);
        assertThat(s.jumpsLeft()).containsExactly(0, 0);
        assertThat(s.exits()).isEmpty();
    }

    @Test
    void client_actions_deserialize_by_name() throws Exception {
        assertThat(mapper.readValue("{\"@action\":\"PLACE_STONE\",\"kind\":\"STRAIGHT\",\"x\":3,\"y\":4}",
                DambadukAction.class)).isEqualTo(new DambadukAction.Place(StoneKind.STRAIGHT, 3, 4));
        assertThat(mapper.readValue("{\"@action\":\"MOVE_STONE\",\"stoneId\":5,\"path\":[\"UP\",\"LEFT\"],\"wall\":\"UP\"}",
                DambadukAction.class)).isEqualTo(new DambadukAction.Move(5, List.of(UP, LEFT), UP, false));
        assertThat(mapper.readValue(
                "{\"@action\":\"MOVE_STONE\",\"stoneId\":5,\"path\":[\"UP\"],\"wall\":\"LEFT\",\"jump\":true}",
                DambadukAction.class)).isEqualTo(new DambadukAction.Move(5, List.of(UP), LEFT, true));
        DambadukAction.Move nulls = (DambadukAction.Move) mapper.readValue(
                "{\"@action\":\"MOVE_STONE\",\"stoneId\":5,\"path\":[\"UP\",null]}", DambadukAction.class);
        assertThat(nulls.wall()).isNull();                                           // 엔진이 BAD_PATH 로 거절한다
        assertThat(nulls.path()).hasSize(2);
    }

    @Test
    void the_server_only_timeout_cannot_come_from_a_client() {
        for (String name : List.of("TimeoutAct", "TIMEOUT", "DambadukAction$TimeoutAct")) {
            assertThatThrownBy(() -> mapper.readValue("{\"@action\":\"" + name + "\"}", DambadukAction.class))
                    .as(name).isInstanceOf(Exception.class);
        }
    }

    @Test
    void events_carry_their_envelope_names_and_are_public_and_sequenced() throws Exception {
        DambadukState s = DambadukStates.threePlaying();
        List<DambadukEvent> events = new java.util.ArrayList<>(ENGINE.apply(s, 0, move(0, LEFT, UP)).events());
        events.addAll(ENGINE.desert(s, 1).events());
        events.addAll(ENGINE.desert(DambadukStates.twoPlaying(), 0).events());
        events.add(new DambadukEvent.TurnSkipped(2));
        events.add(ENGINE.apply(DambadukStates.two(), 0, new DambadukAction.Place(StoneKind.STRAIGHT, 0, 0))
                .events().getFirst());
        assertThat(events).extracting(DambadukEvent::envelopeType).containsExactlyInAnyOrder(
                "STONE_MOVED", "TURN_CHANGED", "SEAT_OUT", "TURN_CHANGED", "SEAT_OUT", "MATCH_ENDED", "TURN_SKIPPED",
                "STONE_PLACED");
        for (DambadukEvent e : events) {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
            JsonNode node = mapper.readTree(mapper.writeValueAsString(e));
            assertThat(node.get("@event").asText()).isEqualTo(e.envelopeType());
            assertThat(keys(node)).doesNotContain("private", "envelopeType", "sequenced");
            assertThat(mapper.readValue(mapper.writeValueAsString(e), DambadukEvent.class)).isEqualTo(e);
        }
        assertThat(keys(mapper.readTree(mapper.writeValueAsString(events.getFirst())))).containsExactly(
                "@event", "fromX", "fromY", "jumpedFace", "path", "seat", "stoneId", "timedOut", "toX", "toY", "wall",
                "wallFace");
    }

    @Test
    void the_public_view_serializes_with_computed_territory() throws Exception {
        JsonNode view = mapper.readTree(mapper.writeValueAsString(ENGINE.publicView(DambadukStates.twoPlaying())));
        assertThat(keys(view)).containsExactly(
                "exits", "firstSeat", "jumpsLeft", "moveCount", "options", "phase", "placeKinds", "placementQueue",
                "result", "seatColors", "seatCount", "stones", "territory", "timeouts", "turnSeat", "walls");
        assertThat(keys(view.get("territory"))).containsExactly("largest", "regions", "scores");
        assertThat(keys(view.get("territory").get("regions").get(0))).containsExactly("cells", "kind", "owner", "seats");
        assertThat(keys(view.get("options").get(0))).containsExactly(
                "jump", "jumpedFace", "path", "stoneId", "toX", "toY", "walls");
    }
}
