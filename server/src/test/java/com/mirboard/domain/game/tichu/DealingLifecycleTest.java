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
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 5b 의 Dealing(8) → Dealing(14) → Passing → Playing 전이를 엔진 레벨에서
 * 검증한다. 통합 테스트는 별도 IT 가 STOMP 라우팅까지 확인.
 */
class DealingLifecycleTest {

    private static final GameContext CTX = new GameContext("test-room", List.of(1L, 2L, 3L, 4L));

    private static Card n(Suit s, int r) {
        return Card.normal(s, r);
    }

    /**
     * 4명의 14장 손패를 합성 (서로 중복 없음, 실제 56장 덱과 동일한 분할):
     * seat 0 = JADE 2..14 + Mahjong, seat 1 = SWORD 2..14 + Dog,
     * seat 2 = PAGODA 2..14 + Phoenix, seat 3 = STAR 2..14 + Dragon.
     * 본 helper 는 그 14장을 visible 8 + reserved 6 으로 분할한 Dealing(8) 반환.
     */
    private static TichuState.Dealing freshDealing8() {
        List<List<Card>> fullHands = controlled14Hands();
        List<PlayerState> players = new ArrayList<>();
        Map<Integer, List<Card>> reserved = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Card> full = fullHands.get(seat);
            players.add(PlayerState.initial(seat, full.subList(0, 8)));
            reserved.put(seat, List.copyOf(full.subList(8, 14)));
        }
        return new TichuState.Dealing(players, 8, Set.of(), reserved);
    }

    @Test
    void grand_tichu_in_dealing_phase_8_marks_seat_ready_and_records_declaration() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState.Dealing dealing = freshDealing8();

        var result = engine.apply(dealing, 1, new TichuAction.DeclareGrandTichu());

        assertThat(result.newState()).isInstanceOf(TichuState.Dealing.class);
        var next = (TichuState.Dealing) result.newState();
        assertThat(next.ready()).containsExactly(1);
        assertThat(next.players().get(1).declaration())
                .isEqualTo(TichuDeclaration.GRAND_TICHU);
        assertThat(result.events()).anyMatch(e -> e instanceof TichuEvent.TichuDeclared);
        assertThat(result.events()).anyMatch(e -> e instanceof TichuEvent.PlayerReady);
    }

    @Test
    void grand_tichu_in_dealing_phase_14_is_rejected() {
        TichuEngine engine = new TichuEngine(CTX);
        // Forced phase 14 dealing with full 14-card hands.
        var fullHands = full14Hands();
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            players.add(PlayerState.initial(seat, fullHands.get(seat)));
        }
        TichuState.Dealing phase14 = new TichuState.Dealing(players, 14, Set.of(), Map.of());

        assertThatThrownBy(() -> engine.apply(phase14, 0, new TichuAction.DeclareGrandTichu()))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.GRAND_TICHU_DECLARATION_WRONG_PHASE);
    }

    @Test
    void four_ready_in_dealing_phase_8_transitions_to_dealing_phase_14() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState state = freshDealing8();

        for (int seat = 0; seat < 4; seat++) {
            state = engine.apply(state, seat, new TichuAction.Ready()).newState();
        }

        assertThat(state).isInstanceOf(TichuState.Dealing.class);
        var next = (TichuState.Dealing) state;
        assertThat(next.phaseCardCount()).isEqualTo(14);
        assertThat(next.ready()).isEmpty();
        assertThat(next.reservedSecondHalf()).isEmpty();
        for (PlayerState p : next.players()) {
            assertThat(p.handSize()).isEqualTo(14);
        }
    }

    @Test
    void four_ready_in_dealing_phase_14_transitions_to_passing() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState state = freshDealing8();
        for (int seat = 0; seat < 4; seat++) {
            state = engine.apply(state, seat, new TichuAction.Ready()).newState();
        }
        // Now Dealing(14). Drive ready again.
        for (int seat = 0; seat < 4; seat++) {
            state = engine.apply(state, seat, new TichuAction.Ready()).newState();
        }

        assertThat(state).isInstanceOf(TichuState.Passing.class);
        var passing = (TichuState.Passing) state;
        assertThat(passing.submitted()).isEmpty();
    }

    @Test
    void tichu_declaration_in_dealing_phase_14_only() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState state = freshDealing8();
        // Drive all 4 to ready in phase 8.
        for (int seat = 0; seat < 4; seat++) {
            state = engine.apply(state, seat, new TichuAction.Ready()).newState();
        }
        // Now phase 14. Seat 1 declares Tichu.
        var result = engine.apply(state, 1, new TichuAction.DeclareTichu());
        assertThat(result.newState()).isInstanceOf(TichuState.Dealing.class);
        var d = (TichuState.Dealing) result.newState();
        assertThat(d.players().get(1).declaration()).isEqualTo(TichuDeclaration.TICHU);
        assertThat(d.ready()).contains(1);
    }

    @Test
    void all_4_pass_cards_transitions_to_playing_with_swap() {
        TichuEngine engine = new TichuEngine(CTX);
        // 14 distinct cards per seat — controlled hands so we can verify swap.
        // Seat 0 has Mahjong; passes Mahjong to seat 1 (left). Verify seat 1 leads.
        List<List<Card>> hands = controlled14Hands();
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            players.add(PlayerState.initial(seat, hands.get(seat)));
        }
        TichuState.Passing passing = new TichuState.Passing(players, Map.of());

        // 좌석 0 (Mahjong 보유자) 가 Mahjong 을 left (seat 1) 로 보냄.
        Card seat0Mahjong = Card.mahjong();
        Card seat0Partner = hands.get(0).stream()
                .filter(c -> c.isNormal() && c.rank() == 3).findFirst().orElseThrow();
        Card seat0Right = hands.get(0).stream()
                .filter(c -> c.isNormal() && c.rank() == 4).findFirst().orElseThrow();

        var afterSeat0 = engine.apply(passing, 0,
                new TichuAction.PassCards(seat0Mahjong, seat0Partner, seat0Right));
        TichuState s = afterSeat0.newState();

        // 좌석 1,2,3 도 각자 자기 손에서 3장 골라 보냄 (단순화 — 모두 rank 5,6,7 선택).
        for (int seat = 1; seat < 4; seat++) {
            List<Card> mine = ((TichuState.Passing) s).players().get(seat).hand();
            Card toLeft = mine.stream().filter(c -> c.isNormal() && c.rank() == 5)
                    .findFirst().orElseThrow();
            Card toPartner = mine.stream().filter(c -> c.isNormal() && c.rank() == 6)
                    .findFirst().orElseThrow();
            Card toRight = mine.stream().filter(c -> c.isNormal() && c.rank() == 7)
                    .findFirst().orElseThrow();
            s = engine.apply(s, seat,
                    new TichuAction.PassCards(toLeft, toPartner, toRight)).newState();
        }

        // Playing 으로 전이. Mahjong 은 seat 1 으로 이동했으므로 lead 가 seat 1.
        assertThat(s).isInstanceOf(TichuState.Playing.class);
        var playing = (TichuState.Playing) s;
        assertThat(playing.trick().leadSeat()).isEqualTo(1);
        assertThat(playing.players().get(1).hand()).contains(Card.mahjong());
        assertThat(playing.players().get(0).hand()).doesNotContain(Card.mahjong());
        // 각자 손패 14장 유지.
        for (PlayerState p : playing.players()) {
            assertThat(p.handSize()).isEqualTo(14);
        }
    }

    @Test
    void duplicate_pass_submission_rejected() {
        TichuEngine engine = new TichuEngine(CTX);
        List<List<Card>> hands = controlled14Hands();
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            players.add(PlayerState.initial(seat, hands.get(seat)));
        }
        Map<Integer, com.mirboard.domain.game.tichu.state.PassCardsSelection> already =
                new LinkedHashMap<>();
        already.put(0, new com.mirboard.domain.game.tichu.state.PassCardsSelection(
                hands.get(0).get(0), hands.get(0).get(1), hands.get(0).get(2)));
        TichuState.Passing passing = new TichuState.Passing(players, already);

        assertThatThrownBy(() -> engine.apply(passing, 0,
                new TichuAction.PassCards(hands.get(0).get(0), hands.get(0).get(1), hands.get(0).get(2))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.ALREADY_PASSED);
    }

    @Test
    void ready_twice_is_rejected() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState state = freshDealing8();
        state = engine.apply(state, 2, new TichuAction.Ready()).newState();

        TichuState finalState = state;
        assertThatThrownBy(() -> engine.apply(finalState, 2, new TichuAction.Ready()))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.ALREADY_READY);
    }

    @Test
    void play_card_in_dealing_phase_is_rejected() {
        TichuEngine engine = new TichuEngine(CTX);
        TichuState.Dealing dealing = freshDealing8();
        Card c = dealing.players().get(0).hand().get(0);

        assertThatThrownBy(() -> engine.apply(dealing, 0,
                new TichuAction.PlayCard(List.of(c))))
                .isInstanceOf(TichuActionRejectedException.class)
                .extracting(t -> ((TichuActionRejectedException) t).reason())
                .isEqualTo(RejectionReason.NOT_IN_PLAYING_PHASE);
    }

    // ---------- helpers ----------

    /** controlled14Hands 와 동일한 분할 — Grand Tichu 거부 케이스용 별칭. */
    private static List<List<Card>> full14Hands() {
        return controlled14Hands();
    }

    /**
     * 좌석별 14장 (서로 중복 없음). 실제 56장 덱과 동일한 분할:
     * seat 0 = JADE 2..14 + Mahjong, seat 1 = SWORD 2..14 + Dog,
     * seat 2 = PAGODA 2..14 + Phoenix, seat 3 = STAR 2..14 + Dragon.
     */
    private static List<List<Card>> controlled14Hands() {
        Suit[] suits = Suit.values();
        Card[] specialsBySeat = {
                Card.mahjong(), Card.dog(), Card.phoenix(), Card.dragon()
        };
        List<List<Card>> hands = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Card> hand = new ArrayList<>();
            for (int r = 2; r <= 14; r++) {
                hand.add(n(suits[seat], r));
            }
            hand.add(specialsBySeat[seat]);
            hands.add(hand);
        }
        return hands;
    }
}
