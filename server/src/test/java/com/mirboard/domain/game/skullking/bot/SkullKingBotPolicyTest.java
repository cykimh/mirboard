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
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-119 — 결정적 휴리스틱 정책. 시나리오는 "사람이라면 이렇게 둔다"가 분명한 국면만
 * 골랐고(가장 싼 승리 카드·가장 위험한 카드 털기·티그리스 선언), 전함수성(어떤 합법수
 * 집합에도 그 원소 하나를 돌려준다)을 따로 고정한다.
 */
class SkullKingBotPolicyTest {

    /** 합법수 계산은 상태만 본다 — 컨텍스트의 인원은 결과와 무관하다. */
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

    /** 좌석 0 의 입찰 국면 — 남의 손패는 뷰에 안 들어가므로 비워 둔다. */
    private static SkullKingState.Bidding bidding(int seatCount, List<SkullCard> hand) {
        List<PlayerState> players = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            players.add(PlayerState.initial(seat, seat == 0 ? hand : List.of()));
        }
        return new SkullKingState.Bidding(hand.size(), players, 0);
    }

    /**
     * {@code seat} 차례의 플레이 국면. {@code trickCards} 는 그 직전 좌석들이 순서대로 낸
     * 카드이고, 승수는 0 이라 남은 필요 승수 = {@code bid} 다.
     */
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
            players.add(s == seat
                    ? new PlayerState(s, hand, bid, List.of())
                    : new PlayerState(s, List.of(), 0, List.of()));
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
    void last_to_play_wins_as_cheaply_as_possible() {
        SkullKingState state = playing(4, 3, 1,
                List.of(green(13), green(14), SkullCard.pirate()),
                List.of(green(10), green(12), green(5)));

        assertThat(choose(state, 3))
                .as("셋 다 확실히 이기면 가장 싼 카드 — 초록 14·해적은 아껴 둔다")
                .isEqualTo(PlayCard.of(green(13)));
    }

    @Test
    void at_bid_sheds_the_biggest_liability_safely() {
        SkullKingState state = playing(2, 1, 0,
                List.of(SkullCard.mermaid(), black(14), SkullCard.escape()),
                List.of(SkullCard.pirate()));

        assertThat(choose(state, 1))
                .as("해적 아래서는 셋 다 진다 — 나중에 떠안기 가장 위험한 인어를 턴다")
                .isEqualTo(PlayCard.of(SkullCard.mermaid()));
    }

    @Test
    void tigress_declaration_follows_need() {
        List<SkullCard> trick = List.of(green(14));

        assertThat(choose(playing(2, 1, 1, List.of(SkullCard.tigress(), green(2)), trick), 1))
                .as("이겨야 하면 해적 선언")
                .isEqualTo(PlayCard.tigress(TigressMode.PIRATE));
        assertThat(choose(playing(2, 1, 0, List.of(SkullCard.tigress(), green(2)), trick), 1))
                .as("져야 하면 티그리스는 보존하고 초록 2 로 진다")
                .isEqualTo(PlayCard.of(green(2)));
        assertThat(choose(playing(2, 1, 0, List.of(SkullCard.tigress()), trick), 1))
                .as("티그리스뿐이면 탈출 선언")
                .isEqualTo(PlayCard.tigress(TigressMode.ESCAPE));
    }

    @Test
    void leads_the_cheapest_likely_winner() {
        SkullKingState state = playing(4, 0, 1,
                List.of(SkullCard.skullKing(), SkullCard.pirate(), green(3)), List.of());

        assertThat(choose(state, 0))
                .as("스컬킹이 내 손에 있으니 해적 리드는 확실 — 스컬킹은 보존")
                .isEqualTo(PlayCard.of(SkullCard.pirate()));
    }

    @Test
    void is_total_when_only_escapes_or_tigress_remain() {
        SkullKingState escapes = playing(4, 0, 1,
                List.of(SkullCard.escape(), SkullCard.escape()), List.of());
        assertThat(choose(escapes, 0)).isEqualTo(PlayCard.of(SkullCard.escape()));

        SkullKingState beaten = playing(2, 1, 1,
                List.of(SkullCard.tigress()), List.of(SkullCard.skullKing()));
        assertThat(choose(beaten, 1))
                .as("이길 수단이 없어도 반드시 하나를 낸다 — 버림 순서상 탈출 선언이 먼저")
                .isEqualTo(PlayCard.tigress(TigressMode.ESCAPE));
    }

    @Test
    void returns_an_element_of_legal_or_null() {
        SkullKingState.Playing state = playing(3, 1, 1,
                List.of(green(9), yellow(14), black(2), SkullCard.escape()),
                List.of(green(5)));
        SkullKingBotView view = SkullKingBotView.of(state, 1);

        assertThat(SkullKingBotPolicy.choose(view, List.of())).isNull();

        List<SkullKingAction> legal = RULES.legalActions(state, 1);
        assertThat(legal)
                .as("초록 리드 — follow 의무로 노랑 14·검정 2 는 빠진다")
                .containsExactly(PlayCard.of(green(9)), PlayCard.of(SkullCard.escape()));
        for (int bid = 0; bid <= 3; bid++) {
            SkullKingState needing = playing(3, 1, bid,
                    List.of(green(9), yellow(14), black(2), SkullCard.escape()),
                    List.of(green(5)));
            assertThat(choose(needing, 1)).isIn(legal);
        }

        SkullKingAction first = SkullKingBotPolicy.choose(view, legal);
        assertThat(SkullKingBotPolicy.choose(view, legal))
                .as("Random 을 쓰지 않는다 — 같은 입력이면 같은 액션")
                .isEqualTo(first);

        SkullKingBotView strong = SkullKingBotView.of(bidding(4, List.of(
                SkullCard.skullKing(), SkullCard.pirate(), SkullCard.pirate())), 0);
        assertThat(SkullKingBotPolicy.choose(strong, List.of(new PlaceBid(0), new PlaceBid(1))))
                .as("목표 예측(3)이 합법수에 없으면 가장 가까운 합법 예측")
                .isEqualTo(new PlaceBid(1));
    }
}
