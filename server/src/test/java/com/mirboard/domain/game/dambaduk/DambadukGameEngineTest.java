package com.mirboard.domain.game.dambaduk;

import static com.mirboard.domain.game.dambaduk.DambadukStates.move;
import static com.mirboard.domain.game.dambaduk.board.Dir.LEFT;
import static com.mirboard.domain.game.dambaduk.board.Dir.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.event.DambadukMatchCompleted;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 포트 어댑터 (D-278) — 상태 I/O·단계 이름·뷰·봇(안전망)·매치 기록 발행·탈주 결과. */
class DambadukGameEngineTest {

    private static final String ROOM = "room-1";

    private final DambadukStateStore store = mock(DambadukStateStore.class);
    private final Map<String, DambadukState> saved = new HashMap<>();
    private final List<Object> published = new ArrayList<>();

    @BeforeEach
    void wireStore() {
        when(store.load(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get((String) i.getArgument(0))));
        doAnswer(i -> {
            saved.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(store).save(anyString(), any());
    }

    private DambadukGameEngine engine(int seats) {
        List<Long> ids = seats == 2 ? List.of(10L, 20L) : List.of(10L, 20L, 30L);
        return new DambadukGameEngine(new GameContext(ROOM, ids, 0, 0, List.of(), Map.of(RoomOption.BOT_LEVEL, "HARD")),
                store, published::add);
    }

    @Test
    void phases_views_and_types() {
        DambadukGameEngine e = engine(2);
        assertThat(e.actionType()).isEqualTo(DambadukAction.class);
        assertThat(e.phaseName(DambadukStates.two())).isEqualTo("PLACING");
        assertThat(e.phaseName(DambadukStates.twoPlaying())).isEqualTo("PLAYING");
        DambadukState ended = DambadukStates.ENGINE.desert(DambadukStates.twoPlaying(), 0).newState();
        assertThat(e.phaseName(ended)).isEqualTo("ENDED");
        assertThat(e.isRoundOver(ended)).isTrue();
        assertThat(e.privateView(DambadukStates.twoPlaying(), 0)).isEmpty();
        assertThat(e.publicView(DambadukStates.twoPlaying())).isEqualTo(DambadukStates.ENGINE.publicView(DambadukStates.twoPlaying()));
        assertThat(e.pendingSeats(DambadukStates.twoPlaying())).containsExactly(0);
        assertThat(e.timeoutAction(DambadukStates.twoPlaying(), 0)).isInstanceOf(DambadukAction.TimeoutAct.class);
        assertThat(e.timer(DambadukStates.twoPlaying())).isEmpty();
        assertThat(e.loadState()).isEmpty();
        assertThat(e.isMatchOver()).isFalse();
        e.saveState(ended);
        assertThat(e.loadState()).contains(ended);
        assertThat(e.isMatchOver()).isTrue();
    }

    @Test
    void apply_passes_through_the_pure_engine() {
        GameEngine.Result r = engine(2).apply(DambadukStates.twoPlaying(), 0, move(4, LEFT, UP));
        assertThat(r.events()).first().isInstanceOf(DambadukEvent.StoneMoved.class);
        assertThat(((DambadukState) r.newState()).moveCount()).isEqualTo(1);
    }

    @Test
    void the_bot_plays_a_legal_move_and_nothing_off_turn() {
        DambadukGameEngine e = engine(2);
        DambadukState s = DambadukStates.twoPlaying();
        GameAction a = e.botAction(s, 0, new Random(1));
        assertThat(e.legalActions(s, 0)).contains(a);
        assertThat(e.botAction(s, 1, new Random(1))).isNull();
        GameAction placing = e.botAction(DambadukStates.three(), 0, new Random(1));
        assertThat(placing).isInstanceOf(DambadukAction.Place.class);
    }

    @Test
    void a_failing_or_illegal_policy_falls_back_to_a_plain_action_not_a_timeout() {
        DambadukGameEngine e = engine(2);
        DambadukState s = DambadukStates.twoPlaying();
        GameAction thrown = e.chooseBotAction(s, 0, (view, legal) -> {
            throw new IllegalStateException("boom");
        });
        assertThat(thrown).isEqualTo(DambadukStates.ENGINE.timeoutMove(s, 0));
        GameAction illegal = e.chooseBotAction(s, 0, (view, legal) -> move(5, UP, UP));
        assertThat(illegal).isEqualTo(DambadukStates.ENGINE.timeoutMove(s, 0));
        GameAction placing = e.chooseBotAction(DambadukStates.two(), 0, (view, legal) -> null);
        assertThat(placing).isEqualTo(new DambadukAction.Place(StoneKind.STRAIGHT, 3, 3));
    }

    @Test
    void advance_records_once_when_the_match_ends() {
        DambadukGameEngine e = engine(2);
        DambadukState s = DambadukStates.twoPlaying();
        GameEngine.Result r = e.apply(s, 0, move(4, LEFT, UP));
        assertThat(e.advance(r.newState(), new ArrayList<>(r.events()))).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).isEmpty();
        DambadukEngine.Desertion d = DambadukStates.ENGINE.desert(s, 1);
        List<GameEvent> outbound = new ArrayList<>(d.events());
        assertThat(e.advance(d.newState(), outbound)).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOfSatisfying(DambadukMatchCompleted.class, m -> {
            assertThat(m.roomId()).isEqualTo(ROOM);
            assertThat(m.playerIds()).containsExactly(10L, 20L);
            assertThat(m.state().result().reason()).isEqualTo(EndReason.DESERTED);
        });
    }

    @Test
    void a_recorder_failure_does_not_stop_the_end() {
        DambadukGameEngine e = new DambadukGameEngine(new GameContext(ROOM, List.of(10L, 20L)), store, event -> {
            throw new IllegalStateException("db down");
        });
        DambadukEngine.Desertion d = DambadukStates.ENGINE.desert(DambadukStates.twoPlaying(), 1);
        assertThat(e.advance(d.newState(), new ArrayList<>(d.events()))).isEqualTo(new GameEngine.Advance(true, true));
    }

    @Test
    void two_player_desertion_ends_and_records() {
        DambadukGameEngine e = engine(2);
        assertThat(e.desert(0, 10L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);   // 상태 없음
        saved.put(ROOM, DambadukStates.twoPlaying());
        List<GameEvent> outbound = new ArrayList<>();
        assertThat(e.desert(1, 20L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(outbound).extracting(GameEvent::envelopeType).containsExactly("SEAT_OUT", "MATCH_ENDED");
        assertThat(saved.get(ROOM).result().winners()).containsExactly(0);
        assertThat(published).hasSize(1);
        assertThat(e.desert(0, 10L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);   // 끝난 판
    }

    @Test
    void three_player_desertion_continues_without_recording() {
        DambadukGameEngine e = engine(3);
        saved.put(ROOM, DambadukStates.threePlaying());
        List<GameEvent> outbound = new ArrayList<>();
        assertThat(e.desert(2, 30L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        assertThat(outbound).extracting(GameEvent::envelopeType).containsExactly("SEAT_OUT", "TURN_CHANGED");
        assertThat(saved.get(ROOM).stonesOf(2)).isEmpty();
        assertThat(published).isEmpty();
        assertThat(e.desert(2, 30L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);   // 이미 빠짐
        verifyNoSaveOfOtherRooms();
    }

    private void verifyNoSaveOfOtherRooms() {
        assertThat(saved.keySet()).containsExactly(ROOM);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.atLeastOnce()).save(eq(ROOM), any());
    }
}
