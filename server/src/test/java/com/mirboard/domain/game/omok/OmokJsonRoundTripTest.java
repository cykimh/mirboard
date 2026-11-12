package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.rules.ForbiddenKind;
import com.mirboard.domain.game.omok.rules.ForbiddenPoint;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * S3b 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 파생 메서드({@code isOver}·{@code board} 등)가
 * 프로퍼티로 새거나, 서버 전용 {@code TimeoutPlace} 를 클라가 만들 수 있게 되는 회귀를 막는다.
 */
class OmokJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void an_ended_team_state_round_trips_and_stores_only_its_components() throws Exception {
        OmokState s = play(teams(OmokRule.DOUBLE_THREE_ONLY), p(7, 7), p(0, 0));
        s = ENGINE.apply(s, s.turnSeat(), ENGINE.timeoutAction(s, s.turnSeat())).newState();
        s = ENGINE.apply(s, 3, new OmokAction.Resign()).newState();
        String json = mapper.writeValueAsString(s);
        assertThat(mapper.readValue(json, OmokState.class)).isEqualTo(s);
        assertThat(keys(mapper.readTree(json))).containsExactly(
                "blackTeam", "boardSize", "firstSeat", "mode", "moves", "result", "rule", "slams", "timeouts", "turnSeat");
        assertThat(keys(mapper.readTree(json).get("result"))).containsExactly(
                "causeSeat", "reason", "winLine", "winnerColor", "winnerSeats");
    }

    @Test
    void a_state_without_timeouts_gets_zeros() throws Exception {
        String json = "{\"mode\":\"SOLO\",\"rule\":\"RENJU\",\"blackTeam\":0,\"firstSeat\":0,\"moves\":[],\"turnSeat\":0}";
        assertThat(mapper.readValue(json, OmokState.class).timeouts()).containsExactly(0, 0);
    }

    @Test
    void a_fifteen_line_state_moves_to_the_middle_of_the_nineteen_line_board() throws Exception {
        // D-193 이전 JSON — boardSize 없음. 흑 (7,7) 백 (0,0), 끝난 판은 승리 줄도 함께 옮긴다.
        String json = "{\"mode\":\"SOLO\",\"rule\":\"RENJU\",\"blackTeam\":0,\"firstSeat\":0,\"moves\":["
                + "{\"seat\":0,\"x\":7,\"y\":7,\"timedOut\":false},{\"seat\":1,\"x\":0,\"y\":0,\"timedOut\":false}],"
                + "\"turnSeat\":0,\"timeouts\":[0,0]}";
        OmokState old = mapper.readValue(json, OmokState.class);
        assertThat(old.boardSize()).isEqualTo(19);
        assertThat(old.moves()).extracting(m -> m.x() + "," + m.y()).containsExactly("9,9", "2,2");
        OmokState next = ENGINE.apply(old, 0, new OmokAction.Place(18, 18)).newState();   // 새 가장자리까지 둘 수 있다
        assertThat(next.moves()).hasSize(3);
        assertThat(mapper.readValue(mapper.writeValueAsString(next), OmokState.class)).isEqualTo(next);   // 다시 옮기지 않는다
        String ended = "{\"mode\":\"SOLO\",\"rule\":\"FREESTYLE\",\"blackTeam\":0,\"firstSeat\":0,\"moves\":[],"
                + "\"turnSeat\":-1,\"result\":{\"winnerColor\":\"BLACK\",\"winnerSeats\":[0],\"reason\":\"FIVE\","
                + "\"causeSeat\":-1,\"winLine\":[{\"x\":0,\"y\":0},{\"x\":4,\"y\":0}]}}";
        assertThat(mapper.readValue(ended, OmokState.class).result().winLine())
                .extracting(pt -> pt.x() + "," + pt.y()).containsExactly("2,2", "6,2");
    }

    @Test
    void a_state_from_before_slams_gets_zeros_and_a_slammed_state_round_trips() throws Exception {
        String json = "{\"mode\":\"TEAMS_2V2\",\"rule\":\"RENJU\",\"blackTeam\":0,\"firstSeat\":0,\"moves\":[],"
                + "\"turnSeat\":0,\"timeouts\":[0,0,0,0]}";
        OmokState old = mapper.readValue(json, OmokState.class);
        assertThat(old.slams()).containsExactly(0, 0, 0, 0);
        OmokState slammed = ENGINE.apply(old, 0, new OmokAction.Place(7, 7, true)).newState();
        assertThat(mapper.readValue(mapper.writeValueAsString(slammed), OmokState.class)).isEqualTo(slammed);
        assertThat(slammed.slams()).containsExactly(1, 0, 0, 0);
    }

    @Test
    void client_actions_deserialize_by_name() throws Exception {
        assertThat(mapper.readValue("{\"@action\":\"PLACE_STONE\",\"x\":3,\"y\":4}", OmokAction.class))
                .isEqualTo(new OmokAction.Place(3, 4));
        assertThat(mapper.readValue("{\"@action\":\"PLACE_STONE\",\"x\":3,\"y\":4,\"slam\":true}", OmokAction.class))
                .isEqualTo(new OmokAction.Place(3, 4, true));
        assertThat(mapper.readValue("{\"@action\":\"PLACE_STONE\",\"x\":3,\"y\":4,\"slam\":false}", OmokAction.class))
                .isEqualTo(new OmokAction.Place(3, 4));
        assertThat(mapper.readValue("{\"@action\":\"RESIGN\"}", OmokAction.class)).isEqualTo(new OmokAction.Resign());
    }

    @Test
    void the_server_only_timeout_place_cannot_come_from_a_client() {
        for (String name : List.of("TimeoutPlace", "TIMEOUT_PLACE", "OmokAction$TimeoutPlace")) {
            assertThatThrownBy(() -> mapper.readValue("{\"@action\":\"" + name + "\"}", OmokAction.class))
                    .as(name).isInstanceOf(Exception.class);
        }
    }

    @Test
    void events_carry_their_envelope_names_and_only_their_fields() throws Exception {
        List<OmokEvent> events = List.of(
                new OmokEvent.MatchStarted(0, List.of(Stone.BLACK, Stone.WHITE)),
                new OmokEvent.StonePlaced(1, Stone.WHITE, 3, 4, 2, false, true),
                new OmokEvent.TurnChanged(0, Stone.BLACK, List.of(new ForbiddenPoint(7, 7, ForbiddenKind.DOUBLE_THREE))),
                new OmokEvent.MatchEnded(null, List.of(), EndReason.BOARD_FULL, -1, List.of(), 225));
        assertThat(events).extracting(OmokEvent::envelopeType)
                .containsExactly("MATCH_STARTED", "STONE_PLACED", "TURN_CHANGED", "MATCH_ENDED");
        for (OmokEvent e : events) {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
            JsonNode node = mapper.readTree(mapper.writeValueAsString(e));
            assertThat(node.get("@event").asText()).isEqualTo(e.envelopeType());
            assertThat(keys(node)).doesNotContain("private", "envelopeType", "sequenced");
            assertThat(mapper.readValue(mapper.writeValueAsString(e), OmokEvent.class)).isEqualTo(e);
        }
        assertThat(keys(mapper.readTree(mapper.writeValueAsString(events.get(1))))).containsExactly(
                "@event", "color", "moveNumber", "seat", "slam", "timedOut", "x", "y");
    }

    @Test
    void the_public_view_has_no_server_internals() throws Exception {
        OmokState s = play(teams(OmokRule.RENJU), p(7, 7), p(0, 0));
        JsonNode view = mapper.readTree(mapper.writeValueAsString(ENGINE.publicView(s)));
        assertThat(keys(view)).containsExactly(
                "boardSize", "forbidden", "mode", "moves", "result", "rule", "seatColors", "slamsLeft", "timeouts", "turnSeat");
        assertThat(view.get("seatColors").toString()).isEqualTo("[\"BLACK\",\"WHITE\",\"BLACK\",\"WHITE\"]");
    }
}
