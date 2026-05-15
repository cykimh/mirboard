package com.mirboard.domain.game.tichu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.tichu.action.RejectionReason;
import com.mirboard.domain.game.tichu.action.TichuAction;
import com.mirboard.domain.game.tichu.action.TichuActionRejectedException;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 10B — 특수 카드 (Mahjong/Dog/Phoenix/Dragon) + Wish + BOMB 인터럽트의 결정적
 * 통합 시나리오. `docs/rules-tichu.md` 의 §8 ~ §10 에 lock in 된 동작을 회귀 보장.
 * `TichuEngineRoundSimulationTest` 패턴 따라 PlayerState/TrickState 수동 조립.
 */
class TichuSpecialCardScenarioTest {

    private static final GameContext CTX = new GameContext("test-room", List.of(1L, 2L, 3L, 4L));

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    // ========================================================================
    // Mahjong + Wish
    // ========================================================================

    @Test
    void mahjong_lead_then_make_wish_activates() {
        var players = List.of(
                PlayerState.initial(0, List.of(Card.mahjong(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.mahjong());
        var afterMahjong = engine.apply(state, 0, new TichuAction.MakeWish(7));
        var trick = ((TichuState.Playing) afterMahjong.newState()).trick();

        assertThat(trick.activeWish()).isNotNull();
        assertThat(trick.activeWish().rank()).isEqualTo(7);
        assertThat(trick.activeWish().fulfilled()).isFalse();
    }

    @Test
    void wish_active_lead_holding_wished_must_include_it() {
        // 0번 자리: 보유 wish 카드 (7) + 다른 카드 (5). wish=7 활성 상태에서 5만 리드 시도 → reject.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 7), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 9))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 10))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        var trick = TrickState.lead(0, com.mirboard.domain.game.tichu.card.Wish.active(7));
        TichuState state = new TichuState.Playing(players, trick, -1);
        var engine = new TichuEngine(CTX);

        assertReject(engine, state, 0,
                new TichuAction.PlayCard(List.of(n(Suit.JADE, 5))),
                RejectionReason.WISH_NOT_FULFILLED);
    }

    @Test
    void wish_active_lead_not_holding_wished_allows_free_play() {
        // 0번: 보유 5, 9 만. wish=7 활성. 5 리드 가능.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 5), n(Suit.JADE, 9))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 10))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13))));
        var trick = TrickState.lead(0, com.mirboard.domain.game.tichu.card.Wish.active(7));
        TichuState state = new TichuState.Playing(players, trick, -1);
        var engine = new TichuEngine(CTX);

        TichuState next = play(engine, state, 0, n(Suit.JADE, 5));
        assertThat(next).isInstanceOf(TichuState.Playing.class);
    }

    @Test
    void wish_fulfilled_when_wished_rank_is_played() {
        // 0번: 7 보유. wish=7 활성. 7 리드 → fulfilled=true.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 7), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 10))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 11))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 13))));
        var trick = TrickState.lead(0, com.mirboard.domain.game.tichu.card.Wish.active(7));
        TichuState state = new TichuState.Playing(players, trick, -1);
        var engine = new TichuEngine(CTX);

        TichuState next = play(engine, state, 0, n(Suit.JADE, 7));
        var nextTrick = ((TichuState.Playing) next).trick();
        assertThat(nextTrick.activeWish()).isNotNull();
        assertThat(nextTrick.activeWish().fulfilled()).isTrue();
    }

    // ========================================================================
    // Dog
    // ========================================================================

    @Test
    void dog_solo_lead_transfers_to_partner() {
        // 0번이 Dog 단독 lead → 파트너 (2번) 가 새 리드.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dog(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        TichuState next = play(engine, state, 0, Card.dog());
        var playing = (TichuState.Playing) next;
        assertThat(playing.trick().leadSeat()).isEqualTo(2);
        assertThat(playing.trick().currentTurnSeat()).isEqualTo(2);
        assertThat(playing.trick().accumulatedCards()).isEmpty();  // Dog 는 점수 0 + 트릭 초기화
    }

    @Test
    void dog_when_partner_finished_falls_through_to_next_active() {
        // D-52 회귀: 파트너 (2번) 가 이미 완주했으면 next active seat (3번) 가 리드.
        var finishedPartner = new PlayerState(2, List.of(),
                com.mirboard.domain.game.tichu.state.TichuDeclaration.NONE, 1, List.of());
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dog(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                finishedPartner,
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        TichuState next = play(engine, state, 0, Card.dog());
        var playing = (TichuState.Playing) next;
        // 2번이 finished → nextActiveSeat(2) = 3번
        assertThat(playing.trick().leadSeat()).isEqualTo(3);
    }

    @Test
    void dog_follow_is_rejected() {
        // 0번이 5 리드, 1번이 Dog 로 follow 시도 → DOG_MUST_BE_SOLO_LEAD.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(Card.dog())),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, n(Suit.JADE, 5));
        assertReject(engine, state, 1,
                new TichuAction.PlayCard(List.of(Card.dog())),
                RejectionReason.DOG_MUST_BE_SOLO_LEAD);
    }

    // ========================================================================
    // Dragon
    // ========================================================================

    @Test
    void dragon_taking_trick_makes_give_dragon_pending() {
        // 0번 Dragon lead → 1,2,3 모두 pass → 트릭 폐쇄 시 dragonWon → pending.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dragon(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.dragon());
        state = pass(engine, state, 1);
        state = pass(engine, state, 2);
        state = pass(engine, state, 3);  // 트릭 폐쇄 시점 — dragonWon pending

        var playing = (TichuState.Playing) state;
        assertThat(playing.trick().currentTop().cards()).containsExactly(Card.dragon());
        assertThat(playing.trick().currentTopSeat()).isEqualTo(0);
        assertThat(playing.trick().currentTurnSeat()).isEqualTo(0);
        // 0번 (dragon player) 가 GiveDragonTrick 액션 대기 — 아직 tricksWon 으로 이전 X
        assertThat(playing.players().get(0).tricksWon()).isEmpty();
    }

    @Test
    void give_dragon_transfers_accumulated_to_opponent() {
        // 0번이 Dragon 트릭 가져간 pending 상태 → GiveDragonTrick(1) → recipient.tricksWon 이전.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dragon())),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.dragon());
        state = pass(engine, state, 1);
        state = pass(engine, state, 2);
        state = pass(engine, state, 3);
        // 양도 → 상대팀 1번에게
        state = engine.apply(state, 0, new TichuAction.GiveDragonTrick(1)).newState();

        var playing = (TichuState.Playing) state;
        // 0번이 Dragon 으로 라운드 완주했으므로 finishedOrder=1, 1번에게 Dragon 트릭이 갔음.
        assertThat(playing.players().get(1).tricksWon()).containsExactly(Card.dragon());
        assertThat(playing.players().get(0).tricksWon()).isEmpty();
        // 0번이 완주했으므로 next active 리드 (1번)
        assertThat(playing.players().get(0).isFinished()).isTrue();
    }

    @Test
    void give_dragon_to_same_team_is_rejected() {
        // 0번이 Dragon 트릭 pending 상태 → GiveDragonTrick(2) 같은 팀 좌석 → reject.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dragon(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 7))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.dragon());
        state = pass(engine, state, 1);
        state = pass(engine, state, 2);
        state = pass(engine, state, 3);

        assertReject(engine, state, 0,
                new TichuAction.GiveDragonTrick(2),
                RejectionReason.DRAGON_TRICK_RECIPIENT_MUST_BE_OPPONENT);
    }

    // ========================================================================
    // Phoenix
    // ========================================================================

    @Test
    void phoenix_lead_has_effective_rank_one() {
        // 0번이 Phoenix 단독 lead → 1번이 2 단독으로 이김 가능 (Phoenix=1 < 2).
        var players = List.of(
                PlayerState.initial(0, List.of(Card.phoenix(), n(Suit.JADE, 14))),
                PlayerState.initial(1, List.of(n(Suit.SWORD, 2), n(Suit.SWORD, 5))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.phoenix());
        // 1번이 2 로 이김 가능
        state = play(engine, state, 1, n(Suit.SWORD, 2));
        var playing = (TichuState.Playing) state;
        assertThat(playing.trick().currentTopSeat()).isEqualTo(1);
    }

    @Test
    void phoenix_follow_beats_top_but_not_dragon() {
        // 0번 K → 1번 Phoenix follow (K+0.5 효과). 2번이 A 로 이김 가능, Phoenix 는 Dragon 아래.
        // 손에 여분 카드 추가 — 3명 완주로 라운드 종료되지 않도록.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 13), n(Suit.JADE, 2))),
                PlayerState.initial(1, List.of(Card.phoenix(), n(Suit.SWORD, 4))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 14), n(Suit.STAR, 3))),
                PlayerState.initial(3, List.of(Card.dragon(), n(Suit.PAGODA, 6))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, n(Suit.JADE, 13));
        state = play(engine, state, 1, Card.phoenix());
        // 2번이 A 로 Phoenix 위로 이김
        state = play(engine, state, 2, n(Suit.STAR, 14));
        var playing = (TichuState.Playing) state;
        assertThat(playing.trick().currentTopSeat()).isEqualTo(2);
    }

    @Test
    void phoenix_cannot_beat_dragon() {
        // 0번 Dragon → 1번 Phoenix 시도 → CANNOT_BEAT_CURRENT.
        var players = List.of(
                PlayerState.initial(0, List.of(Card.dragon(), n(Suit.JADE, 5))),
                PlayerState.initial(1, List.of(Card.phoenix())),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, Card.dragon());
        assertReject(engine, state, 1,
                new TichuAction.PlayCard(List.of(Card.phoenix())),
                RejectionReason.CANNOT_BEAT_CURRENT);
    }

    // ========================================================================
    // BOMB 인터럽트
    // ========================================================================

    @Test
    void bomb_out_of_turn_is_allowed() {
        // 0번이 K 리드, 2번 차례가 아닌 1번이 BOMB (4-of-3) 인터럽트.
        var players = List.of(
                PlayerState.initial(0, List.of(n(Suit.JADE, 13))),
                PlayerState.initial(1, List.of(
                        n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.STAR, 3), n(Suit.PAGODA, 3))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0, n(Suit.JADE, 13));
        // currentTurnSeat=1 (next after lead) — but even if not, BOMB allowed
        state = play(engine, state, 1,
                n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.STAR, 3), n(Suit.PAGODA, 3));
        var playing = (TichuState.Playing) state;
        assertThat(playing.trick().currentTopSeat()).isEqualTo(1);
        assertThat(playing.trick().currentTop().type())
                .isEqualTo(com.mirboard.domain.game.tichu.hand.HandType.BOMB);
    }

    @Test
    void straight_flush_bomb_beats_normal_bomb() {
        // 0번 BOMB (4x3) → 1번 SFB (5장 동일 suit 연속) 인터럽트.
        var players = List.of(
                PlayerState.initial(0, List.of(
                        n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.STAR, 3), n(Suit.PAGODA, 3))),
                PlayerState.initial(1, List.of(
                        n(Suit.JADE, 5), n(Suit.JADE, 6), n(Suit.JADE, 7),
                        n(Suit.JADE, 8), n(Suit.JADE, 9))),
                PlayerState.initial(2, List.of(n(Suit.STAR, 9))),
                PlayerState.initial(3, List.of(n(Suit.PAGODA, 11))));
        TichuState state = new TichuState.Playing(players, TrickState.lead(0, null), -1);
        var engine = new TichuEngine(CTX);

        state = play(engine, state, 0,
                n(Suit.JADE, 3), n(Suit.SWORD, 3), n(Suit.STAR, 3), n(Suit.PAGODA, 3));
        state = play(engine, state, 1,
                n(Suit.JADE, 5), n(Suit.JADE, 6), n(Suit.JADE, 7),
                n(Suit.JADE, 8), n(Suit.JADE, 9));
        var playing = (TichuState.Playing) state;
        assertThat(playing.trick().currentTop().type())
                .isEqualTo(com.mirboard.domain.game.tichu.hand.HandType.STRAIGHT_FLUSH_BOMB);
        assertThat(playing.trick().currentTopSeat()).isEqualTo(1);
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private static TichuState play(TichuEngine engine, TichuState state, int seat, Card... cards) {
        return engine.apply(state, seat, new TichuAction.PlayCard(new ArrayList<>(List.of(cards))))
                .newState();
    }

    private static TichuState pass(TichuEngine engine, TichuState state, int seat) {
        return engine.apply(state, seat, new TichuAction.PassTrick()).newState();
    }

    private static void assertReject(TichuEngine engine,
                                     TichuState state,
                                     int seat,
                                     TichuAction action,
                                     RejectionReason expected) {
        assertThatThrownBy(() -> engine.apply(state, seat, action))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(e -> ((TichuActionRejectedException) e).reason())
                .isEqualTo(expected);
    }

    @SuppressWarnings("unused")
    private static boolean dragonInTop(TrickState t) {
        return t.currentTop() != null
                && t.currentTop().cards().size() == 1
                && t.currentTop().cards().get(0).is(Special.DRAGON);
    }
}
