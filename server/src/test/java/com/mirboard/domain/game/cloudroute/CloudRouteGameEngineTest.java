package com.mirboard.domain.game.cloudroute;

import static com.mirboard.domain.game.cloudroute.CloudRouteTables.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.cloudroute.action.CloudRouteAction;
import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.event.CloudRouteMatchCompleted;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.Pending;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEngine.DesertOutcome;
import com.mirboard.domain.game.core.GameEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** D-212 — 포트 어댑터: 상태 I/O·뷰·봇 안전망·기록 발행·탈주 3치. */
class CloudRouteGameEngineTest {

    private final CloudRouteStateStore store = mock(CloudRouteStateStore.class);
    private final AtomicReference<CloudRouteState> stored = new AtomicReference<>();
    private final List<Object> published = new ArrayList<>();

    private CloudRouteGameEngine engine(int seats, Integer... bots) {
        when(store.load("t")).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq("t"), any());
        return new CloudRouteGameEngine(CloudRouteTables.context(seats, bots), store, new Random(5), published::add);
    }

    @Test
    void the_public_view_hides_the_letter_order_and_there_is_no_private_view() {
        CloudRouteGameEngine e = engine(2);
        CloudRouteState s = table(2).own(1, 7, 2).deckTop(Letter.GUST, Letter.BUOY).build();
        JsonNode json = new ObjectMapper().valueToTree(e.publicView(s));
        assertThat(json.toString()).doesNotContain("letterDeck").doesNotContain("GUST");
        assertThat(json.get("deckCount").asInt()).isEqualTo(10);
        assertThat(json.get("seats").get(1).get("totalAssets").asInt()).isEqualTo(1_500 + 100 + 2 * 50);
        assertThat(json.get("pending").get("kind").asText()).isEqualTo("ROLL");
        assertThat(e.privateView(s, 0)).isEmpty();
        assertThat(e.timer(s)).isEmpty();
        assertThat(e.phaseName(s)).isEqualTo("AWAIT_ROLL");
    }

    @Test
    void a_transition_that_ends_the_match_publishes_one_record_and_others_publish_none() {
        CloudRouteGameEngine e = engine(2);
        CloudRouteState open = table(2).build();
        GameEngine.Result rolled = e.apply(open, 0, new CloudRouteAction.Roll());
        assertThat(e.advance(rolled.newState(), new ArrayList<>(rolled.events()))).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).isEmpty();

        // 마지막 라운드의 마지막 차례 — 정해 둔 주사위로 출발 항구에 멈춰 라운드 상한 종료.
        CloudRouteState lastTurn = table(2).turn(1).round(30).at(1, 19).build();
        CloudRouteEngine.Result end = CloudRouteTables.engine(2, new CloudRouteTables.ScriptedDice(2, 3))
                .apply(lastTurn, 1, new CloudRouteAction.Roll());
        assertThat(end.newState().ended()).isTrue();
        assertThat(e.advance(end.newState(), new ArrayList<>(end.events()))).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).hasSize(1).first().isInstanceOf(CloudRouteMatchCompleted.class);
        // 이미 끝난 상태로 다시 불려도 MATCH_ENDED 가 없으면 두 번 기록하지 않는다.
        assertThat(e.advance(end.newState(), new ArrayList<>())).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
    }

    @Test
    void desertion_maps_to_the_three_port_outcomes_and_records_only_an_ending() {
        CloudRouteGameEngine e = engine(3);
        stored.set(table(3).build());
        List<GameEvent> out = new ArrayList<>();
        assertThat(e.desert(0, 100, out)).isEqualTo(DesertOutcome.MATCH_CONTINUES);
        assertThat(out).isNotEmpty();
        assertThat(published).isEmpty();
        assertThat(e.desert(0, 100, new ArrayList<>())).isEqualTo(DesertOutcome.NOT_APPLICABLE);
        assertThat(e.desert(1, 101, new ArrayList<>())).isEqualTo(DesertOutcome.MATCH_ENDED);
        assertThat(published).hasSize(1);
        assertThat(e.isMatchOver()).isTrue();
        stored.set(null);
        assertThat(e.desert(2, 102, new ArrayList<>())).isEqualTo(DesertOutcome.NOT_APPLICABLE);
    }

    @Test
    void a_failing_recorder_does_not_stop_the_ending() {
        CloudRouteGameEngine e = new CloudRouteGameEngine(CloudRouteTables.context(2), store, new Random(5), event -> {
            throw new IllegalStateException("db down");
        });
        when(store.load("t")).thenReturn(Optional.of(table(2).build()));
        assertThat(e.desert(1, 101, new ArrayList<>())).isEqualTo(DesertOutcome.MATCH_ENDED);
    }

    @Test
    void the_bot_falls_back_to_the_timeout_action_when_its_policy_breaks() {
        CloudRouteGameEngine e = engine(2);
        CloudRouteState s = table(2).at(0, 4).pending(new Pending.AwaitPurchase(4, 2)).build();
        assertThat(e.chooseBotAction(s, 0, (view, legal) -> {
            throw new IllegalStateException("bug");
        })).isEqualTo(new CloudRouteAction.Decline());
        assertThat(e.chooseBotAction(s, 0, (view, legal) -> new CloudRouteAction.Fly(3)))
                .isEqualTo(new CloudRouteAction.Decline());
        assertThat(e.chooseBotAction(s, 1, (view, legal) -> new CloudRouteAction.Roll())).isNull();
        GameAction chosen = e.botAction(s, 0, new Random(1));
        assertThat(e.legalActions(s, 0)).contains(chosen);
    }

    @Test
    void the_engine_reads_and_writes_the_room_state() {
        CloudRouteGameEngine e = engine(2);
        CloudRouteState s = table(2).eliminated(1, Elimination.Reason.DESERTED).build();
        e.saveState(s);
        assertThat(e.loadState()).contains(s);
        assertThat(e.actionType()).isEqualTo(CloudRouteAction.class);
        assertThat(e.pendingSeats(s)).containsExactly(0);
    }
}
