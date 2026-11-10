package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlaceBid;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlayCard;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-230 — 상대 모델 봇. 시나리오는 표본과 무관하게 답이 분명한 국면만 고르고(이겨야 하면
 * 이기는 수, 져야 하면 탈출·티그리스를 아끼는 수, 센 손패면 높은 예측), 전함수성·결정성을
 * 무작위 국면에서 따로 고정한다. 강도는 {@link SkullKingBotStrengthTest}.
 */
class SkullKingBotPolicyTest {

    private static final SkullKingEngine RULES =
            new SkullKingEngine(new GameContext("bot-policy", List.of(1L, 2L)));

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    private static SkullCard yellow(int rank) {
        return SkullCard.of(SkullSuit.YELLOW, rank);
    }

    private static SkullCard black(int rank) {
        return SkullCard.of(SkullSuit.BLACK, rank);
    }

    private static SkullKingState.Bidding bidding(int seatCount, List<SkullCard> hand) {
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            players.add(PlayerState.initial(seat, seat == 0 ? hand : List.of()));
        }
        return new SkullKingState.Bidding(hand.size(), players, 0);
    }

    /** {@code seat} 차례의 플레이 국면 — 남의 예측은 0, 승수 0. */
    private static SkullKingState.Playing playing(int seatCount, int seat, int bid,
                                                  List<SkullCard> hand,
                                                  List<SkullCard> trickCards) {
        int lead = Math.floorMod(seat - trickCards.size(), seatCount);
        List<PlayedCard> played = new ArrayList<>();
        for (int i = 0; i < trickCards.size(); i++) {
            played.add(PlayedCard.of((lead + i) % seatCount, trickCards.get(i)));
        }
        List<PlayerState> players = new ArrayList<>();
        for (int s = 0; s < seatCount; s++) {
            players.add(new PlayerState(s, s == seat ? hand : List.of(), s == seat ? bid : 0,
                    List.of()));
        }
        return new SkullKingState.Playing(
                Math.max(1, hand.size()), players, lead, new TrickState(lead, played));
    }

    private static SkullKingAction choose(SkullKingState state, int seat) {
        List<SkullKingAction> legal = RULES.legalActions(state, seat);
        SkullKingAction action = SkullKingBotPolicy.choose(SkullKingBotView.of(state, seat), legal);
        assertThat(legal).as("정책 결과는 언제나 합법수의 원소").contains(action);
        return action;
    }

    @Test
    void bids_track_hand_strength() {
        assertThat(choose(bidding(4, List.of(
                SkullCard.skullKing(), SkullCard.pirate(), SkullCard.pirate())), 0))
                .isEqualTo(new PlaceBid(3));
        assertThat(choose(bidding(4, List.of(SkullCard.escape(), green(2), yellow(3))), 0))
                .isEqualTo(new PlaceBid(0));
    }

    @Test
    void must_win_now_declares_tigress_as_pirate() {
        // 2인 · 남은 두 트릭을 다 이겨야 한다. 초록 2 는 초록 14 를 못 이긴다.
        SkullKingState state = playing(2, 1, 2, List.of(SkullCard.tigress(), green(2)),
                List.of(green(14)));
        assertThat(choose(state, 1)).isEqualTo(PlayCard.tigress(TigressMode.PIRATE));
    }

    @Test
    void at_bid_keeps_the_tigress_for_later() {
        // 0 예측을 지켜야 한다 — 초록 2 로 지금 지면 다음 트릭은 티그리스 탈출 선언으로 확실히 진다.
        SkullKingState state = playing(2, 1, 0, List.of(SkullCard.tigress(), green(2)),
                List.of(green(14)));
        assertThat(choose(state, 1)).isEqualTo(PlayCard.of(green(2)));
    }

    @Test
    void at_bid_saves_the_escape_while_everything_loses() {
        SkullKingState state = playing(2, 1, 0,
                List.of(SkullCard.mermaid(), black(14), SkullCard.escape()),
                List.of(SkullCard.pirate()));
        assertThat(choose(state, 1))
                .as("해적 아래서는 셋 다 진다 — 확실히 지는 탈출은 나중을 위해 남긴다")
                .isNotEqualTo(PlayCard.of(SkullCard.escape()));
    }

    @Test
    void last_to_play_with_need_takes_the_trick() {
        SkullKingState state = playing(4, 3, 1,
                List.of(green(3), green(13), SkullCard.escape()),
                List.of(green(10), green(12), green(5)));
        assertThat(choose(state, 3)).isEqualTo(PlayCard.of(green(13)));
    }

    @Test
    void is_total_deterministic_and_legal_on_random_positions() {
        Random random = new Random(230L);
        for (int n = 2; n <= 8; n++) {
            List<Long> ids = new ArrayList<>();
            for (long i = 0; i < n; i++) {
                ids.add(i + 1);
            }
            SkullKingEngine engine = new SkullKingEngine(new GameContext("policy", ids));
            for (int round = 1; round <= SkullKingMatchState.TOTAL_ROUNDS; round += 3) {
                SkullKingState state = engine.startRound(
                        new SkullKingMatchState(round, 0, Map.of()), random).newState();
                while (!engine.isRoundOver(state)) {
                    int seat = engine.pendingSeats(state).get(0);
                    List<SkullKingAction> legal = engine.legalActions(state, seat);
                    SkullKingBotView view = SkullKingBotView.of(state, seat);
                    SkullKingAction action = SkullKingBotPolicy.choose(view, legal);
                    assertThat(legal).contains(action);
                    assertThat(SkullKingBotPolicy.choose(view, legal))
                            .as("같은 뷰면 같은 수 — 시간·외부 난수 없음").isEqualTo(action);
                    state = engine.apply(state, seat, action).newState();
                }
            }
        }
        assertThat(SkullKingBotPolicy.choose(
                SkullKingBotView.of(bidding(2, List.of(green(1))), 0), List.of())).isNull();
    }
}
