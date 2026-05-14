package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 한 라운드를 끝까지 시뮬레이션. 작은 손패(1~2장) 로 빠르게 종료시키되, 트릭 폐쇄·
 * 플레이어 완주·라운드 종료 + 점수 계산까지 전체 경로를 검증.
 */
class TichuEngineRoundSimulationTest {

    private static final GameContext CTX = new GameContext("test-room", List.of(1L, 2L, 3L, 4L));

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    @Test
    void three_finish_round_ends_with_score() {
        // p0(Team A): [10]  → plays 10, finishes 1st
        // p1(Team B): [11]  → plays 11, finishes 2nd
        // p2(Team A): [12]  → plays 12, finishes 3rd → round ends
        // p3(Team B): [5, 13] → loser (5+10=15 points in hand → opposing team A)
        // p2 wins the in-progress trick: tricksWon=[10, 11, 12] = 10 points (only 10 scores).
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 10))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 11))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 12))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 5), n(Suit.PAGODA, 13))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, n(Suit.JADE, 10));
        state = play(engine, state, 1, n(Suit.SWORD, 11));
        state = play(engine, state, 2, n(Suit.STAR, 12));

        assertThat(state).isInstanceOf(TichuState.RoundEnd.class);
        var round = (TichuState.RoundEnd) state;
        // Team A: p2 trick(10) + loser hand(15) + loser tricks(0) = 25
        // Team B: 0
        assertThat(round.teamAScore()).isEqualTo(25);
        assertThat(round.teamBScore()).isZero();
    }

    @Test
    void double_victory_ends_round_immediately_after_partners_finish() {
        // p0 and p2 (Team A) finish 1st and 2nd. p1 and p3 still have cards.
        // p0: [9] → plays, finishes 1st
        // p1: [3]  → passes
        // p2: [13] (King) → beats 9, but then plays again on lead with [2]?
        // Simpler: each plays once, leading to double victory.
        // Realistically need a sequence. Let me design:
        //   Trick 1:
        //     p0 lead → 9 (finishes 1st)
        //     p1 → pass
        //     p2 → pass (we want partner alive for next trick)
        //     p3 → pass
        //   Trick taken by p0 (already finished). Lead passes to p1 (next active).
        // Hmm getting complex. Let me use a different structure.
        //
        // p0: [10], p2: [11], p1: [3, 4], p3: [5, 6]
        //   Trick 1: p0 plays 10. p1 pass. p2 plays 11 (beats). p3 pass. p0... finished? wait p0 played and finished.
        // After p0 finishes, advanceTurn skips them.
        //   p1 pass, p2 (currentTopSeat) — turn returns to currentTopSeat=p2 → trick closes.
        //   p2 takes [10, 11]. p2 leads next.
        //   p2 has [] — finished 2nd. Round ends via double victory.
        //
        // Wait p2 wins the trick BEFORE finishing? Their tricksWon updates first then we
        // check round end. They finished when they played 11 (last card). Then trick closes,
        // tricks transferred, and round end check (2 same-team finished) triggers double victory.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 10))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 3), n(Suit.SWORD, 4))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 5), n(Suit.PAGODA, 6))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, n(Suit.JADE, 10));
        state = pass(engine, state, 1);
        state = play(engine, state, 2, n(Suit.STAR, 11));
        state = pass(engine, state, 3);

        // Round should have ended (double victory).
        assertThat(state).isInstanceOf(TichuState.RoundEnd.class);
        var round = (TichuState.RoundEnd) state;
        assertThat(round.teamAScore()).isEqualTo(200);
        assertThat(round.teamBScore()).isZero();
    }

    @Test
    void engine_advances_turn_and_emits_events() {
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 5), n(Suit.JADE, 6))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 8))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        var result = engine.apply(state, 0,
                new TichuAction.PlayCard(List.of(n(Suit.JADE, 5))));

        assertThat(result.events()).anySatisfy(e ->
                assertThat(e).isInstanceOf(
                        com.mirboard.domain.game.tichu.event.TichuEvent.Played.class));
        assertThat(result.events()).anySatisfy(e ->
                assertThat(e).isInstanceOf(
                        com.mirboard.domain.game.tichu.event.TichuEvent.TurnChanged.class));

        var nextState = (TichuState.Playing) result.newState();
        assertThat(nextState.trick().currentTurnSeat()).isEqualTo(1);
        assertThat(nextState.trick().currentTop()).isNotNull();
        assertThat(nextState.trick().currentTopSeat()).isZero();
    }

    @Test
    void player_finishing_emits_player_finished_event() {
        // p0 has only [10]; plays it → finishes 1st.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 10))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7), n(Suit.SWORD, 8))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9), n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 6), n(Suit.PAGODA, 12))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        var result = engine.apply(state, 0,
                new TichuAction.PlayCard(List.of(n(Suit.JADE, 10))));

        assertThat(result.events()).anySatisfy(e -> {
            if (e instanceof com.mirboard.domain.game.tichu.event.TichuEvent.PlayerFinished pf) {
                assertThat(pf.seat()).isZero();
                assertThat(pf.order()).isEqualTo(1);
            }
        });

        var next = (TichuState.Playing) result.newState();
        assertThat(next.players().get(0).isFinished()).isTrue();
        assertThat(next.players().get(0).finishedOrder()).isEqualTo(1);
    }

    @Test
    void pass_closes_trick_when_all_others_pass() {
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 10), n(Suit.JADE, 11))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 3), n(Suit.SWORD, 4))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 5), n(Suit.STAR, 6))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 7), n(Suit.PAGODA, 8))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        // p0 plays 10 (high single nobody beats).
        state = play(engine, state, 0, n(Suit.JADE, 10));
        state = pass(engine, state, 1);
        state = pass(engine, state, 2);
        state = pass(engine, state, 3);

        var playing = (TichuState.Playing) state;
        // p0 takes the trick (only the 10 in accumulated since others passed).
        assertThat(playing.players().get(0).tricksWon()).containsExactly(n(Suit.JADE, 10));
        // New trick: p0 leads again.
        assertThat(playing.trick().leadSeat()).isZero();
        assertThat(playing.trick().currentTurnSeat()).isZero();
        assertThat(playing.trick().currentTop()).isNull();
    }

    // ---------- helpers ----------

    private static TichuState play(TichuEngine engine, TichuState state, int seat, Card... cards) {
        return engine.apply(state, seat, new TichuAction.PlayCard(new ArrayList<>(List.of(cards))))
                .newState();
    }

    private static TichuState pass(TichuEngine engine, TichuState state, int seat) {
        return engine.apply(state, seat, new TichuAction.PassTrick()).newState();
    }
}
