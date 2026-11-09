package com.mirboard.domain.game.cloudroute;

import static com.mirboard.domain.game.cloudroute.CloudRouteTables.table;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.event.CloudRouteEvent;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.Pending;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * S2 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 판정 메서드가 프로퍼티로 새거나(원카드·스컬킹 선례) 다형
 * 이름이 envelope 와 어긋나는 회귀를 여기서 막는다.
 */
class CloudRouteJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    @Test
    void states_with_every_pending_kind_round_trip() throws Exception {
        List<Pending> pendings = List.of(new Pending.AwaitRoll(), new Pending.AwaitPurchase(4, 2),
                new Pending.AwaitBuild(4, 2, 3), new Pending.AwaitTakeover(7, 1, 300),
                new Pending.AwaitFestival(List.of(1, 2)), new Pending.AwaitFlight(List.of(0, 1)));
        for (Pending pending : pendings) {
            CloudRouteState s = table(3).own(0, 4, 1).own(1, 7, 2).festival(7).buoy(2).locked(4)
                    .eliminated(2, Elimination.Reason.BANKRUPT).pending(pending).build();
            assertThat(roundTrip(s, CloudRouteState.class)).isEqualTo(s);
        }
        CloudRouteState trapped = table(2).trapped(0, 1).build();
        assertThat(roundTrip(trapped, CloudRouteState.class)).isEqualTo(trapped);
    }

    @Test
    void a_finished_state_round_trips_and_leaks_no_derived_properties() throws Exception {
        CloudRouteState open = table(2).build();
        CloudRouteState end = new CloudRouteEngine(CloudRouteTables.context(2), new Random(1)).desert(open, 1).newState();
        assertThat(roundTrip(end, CloudRouteState.class)).isEqualTo(end);
        JsonNode json = mapper.valueToTree(end);
        assertThat(json.has("ended")).isFalse();
        assertThat(json.has("aliveSeats")).isFalse();
    }

    @Test
    void every_action_round_trips_with_its_wire_name() throws Exception {
        List<CloudRouteAction> actions = List.of(new CloudRouteAction.Roll(), new CloudRouteAction.PayEscape(),
                new CloudRouteAction.UseBuoy(), new CloudRouteAction.Buy(2), new CloudRouteAction.Decline(),
                new CloudRouteAction.Build(3), new CloudRouteAction.Skip(), new CloudRouteAction.Takeover(),
                new CloudRouteAction.HostFestival(5), new CloudRouteAction.Fly(9));
        for (CloudRouteAction action : actions) {
            assertThat(roundTrip(action, CloudRouteAction.class)).isEqualTo(action);
        }
        assertThat(mapper.readValue("{\"@action\":\"BUY\",\"buildTo\":1}", CloudRouteAction.class))
                .isEqualTo(new CloudRouteAction.Buy(1));
    }

    @Test
    void events_carry_their_envelope_name_and_no_private_flag() throws Exception {
        List<CloudRouteEvent> events = List.of(
                new CloudRouteEvent.DiceRolled(0, 2, 2, true, 1),
                new CloudRouteEvent.LetterDrawn(0, Letter.GUST, 9, false),
                new CloudRouteEvent.DecisionRequired(0, new Pending.AwaitFlight(List.of(1))),
                new CloudRouteEvent.AssetsLiquidated(1, List.of(new CloudRouteEvent.AssetsLiquidated.Sale(4, true, 0, 40)), 90),
                new CloudRouteEvent.PlayerEliminated(1, Elimination.Reason.DESERTED, -1, List.of(4), 90));
        for (CloudRouteEvent event : events) {
            JsonNode json = mapper.valueToTree(event);
            assertThat(json.get("@event").asText()).isEqualTo(event.envelopeType());
            assertThat(json.has("private")).isFalse();
            assertThat(roundTrip(event, CloudRouteEvent.class)).isEqualTo(event);
        }
    }

    @Test
    void the_deck_order_is_the_only_hidden_field_and_lives_only_in_the_state() throws Exception {
        // 이벤트에는 더미 순서가 없다(남은 장수만) — 공개 뷰(S2)도 장수만 싣는다(룰 §13).
        JsonNode drawn = mapper.valueToTree(new CloudRouteEvent.LetterDrawn(0, Letter.GUST, 9, false));
        assertThat(drawn.has("letterDeck")).isFalse();
        assertThat(drawn.get("deckCount").asInt()).isEqualTo(9);
    }
}
