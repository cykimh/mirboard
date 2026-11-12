package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.event.OmokMatchCompleted;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokPublicView;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 포트 어댑터 (D-175) — 비공개 뷰 없음, 기록 발행 한 번, 탈주는 매치 종료, 봇은 방 설정의 난이도. */
class OmokGameEngineTest {

    private static final String ROOM = "r1";
    private final OmokStateStore store = mock(OmokStateStore.class);
    private final AtomicReference<OmokState> stored = new AtomicReference<>();
    private final List<Object> published = new ArrayList<>();

    private OmokGameEngine engine(Map<RoomOption, String> options) {
        when(store.load(ROOM)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(store).save(eq(ROOM), any());
        return new OmokGameEngine(new GameContext(ROOM, List.of(10L, 20L), 0, 0, List.of(), options), store,
                published::add);
    }

    private static OmokState fourInARow() {
        // 흑 (3..6, 7) 넷, 흑 차례.
        return play(OmokStates.solo(OmokRule.RENJU), p(3, 7), p(0, 0), p(4, 7), p(0, 2), p(5, 7), p(0, 4), p(6, 7),
                p(0, 6));
    }

    @Test
    void there_is_no_private_view_and_the_public_view_is_the_whole_state() {
        OmokGameEngine e = engine(Map.of());
        OmokState s = fourInARow();
        assertThat(e.privateView(s, 0)).isEmpty();
        assertThat(e.privateView(s, 1)).isEmpty();
        assertThat(e.publicView(s)).isInstanceOfSatisfying(OmokPublicView.class,
                v -> assertThat(v.moves()).hasSize(8));
        assertThat(e.phaseName(s)).isEqualTo("PLAYING");
        assertThat(e.pendingSeats(s)).containsExactly(0);
        assertThat(e.actionType()).isEqualTo(OmokAction.class);
    }

    @Test
    void a_slam_goes_through_the_adapter_and_the_bot_never_slams() {
        OmokGameEngine e = engine(Map.of());
        OmokState s = OmokStates.solo(OmokRule.RENJU);
        GameEngine.Result r = e.apply(s, 0, new OmokAction.Place(7, 7, true));
        assertThat(r.events().getFirst()).isInstanceOfSatisfying(OmokEvent.StonePlaced.class,
                ev -> assertThat(ev.slam()).isTrue());
        // 봇은 강하게 두지 않는다(D-193) — 백 봇이 고른 수도, 합법 수 목록도 전부 보통 착수.
        OmokState white = (OmokState) r.newState();
        assertThat(e.legalActions(white, 1)).allSatisfy(a ->
                assertThat(((OmokAction.Place) a).slam()).isFalse());
        assertThat(e.botAction(white, 1, new Random(1))).isInstanceOfSatisfying(OmokAction.Place.class,
                a -> assertThat(a.slam()).isFalse());
    }

    @Test
    void a_winning_move_advances_once_and_publishes_one_record() {
        OmokGameEngine e = engine(Map.of());
        GameEngine.Result r = e.apply(fourInARow(), 0, new OmokAction.Place(7, 7));
        List<GameEvent> outbound = new ArrayList<>(r.events());
        assertThat(e.isRoundOver(r.newState())).isTrue();
        assertThat(e.advance(r.newState(), outbound)).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOfSatisfying(OmokMatchCompleted.class, c -> {
            assertThat(c.playerIds()).containsExactly(10L, 20L);
            assertThat(c.state().result().reason()).isEqualTo(EndReason.FIVE);
        });
        // 이미 끝난 상태로 다시 불려도(이벤트 없음) 두 번 기록하지 않는다.
        assertThat(e.advance(r.newState(), List.of())).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
        assertThat(e.phaseName(r.newState())).isEqualTo("ENDED");
    }

    @Test
    void a_record_failure_does_not_escape_the_progress_path() {
        OmokGameEngine e = new OmokGameEngine(new GameContext(ROOM, List.of(10L, 20L)), store, event -> {
            throw new IllegalStateException("db down");
        });
        GameEngine.Result r = e.apply(fourInARow(), 0, new OmokAction.Place(7, 7));
        assertThat(e.advance(r.newState(), new ArrayList<>(r.events()))).isEqualTo(new GameEngine.Advance(true, true));
    }

    @Test
    void desertion_ends_the_match_saves_and_records() {
        OmokGameEngine e = engine(Map.of());
        stored.set(fourInARow());
        List<GameEvent> outbound = new ArrayList<>();
        assertThat(e.desert(1, 20L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(stored.get().result().reason()).isEqualTo(EndReason.DESERTED);
        assertThat(outbound).singleElement().isInstanceOf(OmokEvent.MatchEnded.class);
        assertThat(published).hasSize(1);
        assertThat(e.isMatchOver()).isTrue();
        // 끝난 뒤의 나가기는 해당 없음.
        assertThat(e.desert(0, 10L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        assertThat(published).hasSize(1);
    }

    @Test
    void desertion_without_a_state_is_not_applicable() {
        assertThat(engine(Map.of()).desert(0, 10L, new ArrayList<>())).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
    }

    @Test
    void the_bot_plays_a_legal_place_and_takes_the_five() {
        OmokGameEngine e = engine(Map.of(RoomOption.BOT_LEVEL, "HARD"));
        OmokState s = fourInARow();
        assertThat(e.botAction(s, 0, new Random(1))).isIn(new OmokAction.Place(2, 7), new OmokAction.Place(7, 7));
        assertThat(e.botAction(s, 1, new Random(1))).as("남의 차례").isNull();
    }

    @Test
    void a_failing_policy_falls_back_to_a_plain_place_not_a_timeout() {
        OmokGameEngine e = engine(Map.of());
        OmokState s = fourInARow();
        var action = e.chooseBotAction(s, 0, (view, legal) -> {
            throw new IllegalStateException("policy bug");
        });
        assertThat(action).isInstanceOf(OmokAction.Place.class);
        OmokState next = (OmokState) e.apply(s, 0, action).newState();
        assertThat(next.timeouts()).containsExactly(0, 0);              // 봇의 시간 초과 수가 오르지 않는다
        assertThat(e.chooseBotAction(s, 0, (view, legal) -> new OmokAction.Place(Board.SIZE - 1, Board.SIZE)))
                .as("합법수 밖").isInstanceOf(OmokAction.Place.class).isNotEqualTo(new OmokAction.Place(Board.SIZE - 1, Board.SIZE));
    }

    @Test
    void the_timeout_action_is_the_server_only_timeout_place() {
        assertThat(engine(Map.of()).timeoutAction(fourInARow(), 0)).isInstanceOf(OmokAction.TimeoutPlace.class);
    }
}
