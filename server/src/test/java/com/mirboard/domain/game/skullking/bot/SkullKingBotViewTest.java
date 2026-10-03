package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.state.TrickState;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-119 — 봇 정보 경계. 정책이 볼 수 있는 것은 {@link SkullKingBotView} 뿐이고, 뷰는
 * <b>공개 이벤트로 이미 공개된 정보</b>(본인 손패·본인 예측/승수·현재 트릭·이번 라운드에
 * 낸 카드)만 담는다. 남의 손패나 공개 전 예측이 다른 두 상태에서 뷰와 정책 출력이 같으면
 * 그 정보는 새지 않은 것이다.
 */
class SkullKingBotViewTest {

    private static final SkullKingEngine RULES =
            new SkullKingEngine(new GameContext("bot-view", List.of(1L, 2L, 3L, 4L)));

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    private static SkullCard yellow(int rank) {
        return SkullCard.of(SkullSuit.YELLOW, rank);
    }

    private static SkullCard purple(int rank) {
        return SkullCard.of(SkullSuit.PURPLE, rank);
    }

    private static SkullCard black(int rank) {
        return SkullCard.of(SkullSuit.BLACK, rank);
    }

    private static TrickResult resolved(PlayedCard... cards) {
        return TrickResolver.resolve(List.of(cards));
    }

    private static SkullKingAction chosen(SkullKingState state, int seat) {
        return SkullKingBotPolicy.choose(
                SkullKingBotView.of(state, seat), RULES.legalActions(state, seat));
    }

    @Test
    void bidding_view_hides_other_hands_and_unrevealed_bids() {
        List<SkullCard> mine = List.of(SkullCard.skullKing(), green(5), black(3));
        List<SkullCard> handA = List.of(SkullCard.pirate(), yellow(7), purple(2));
        List<SkullCard> handB = List.of(SkullCard.mermaid(), green(9), SkullCard.escape());
        List<SkullCard> handC = List.of(black(12), yellow(1), purple(14));

        // 좌석 1·2 의 손패가 서로 바뀌어 있고, 좌석 1 의 예측은 한쪽에서만 제출됐다.
        SkullKingState.Bidding before = new SkullKingState.Bidding(3, List.of(
                PlayerState.initial(0, mine),
                PlayerState.initial(1, handA),
                PlayerState.initial(2, handB),
                PlayerState.initial(3, handC).withBid(1)), 0);
        SkullKingState.Bidding after = new SkullKingState.Bidding(3, List.of(
                PlayerState.initial(0, mine),
                PlayerState.initial(1, handB).withBid(3),
                PlayerState.initial(2, handA),
                PlayerState.initial(3, handC).withBid(2)), 0);

        SkullKingBotView view = SkullKingBotView.of(before, 0);
        assertThat(SkullKingBotView.of(after, 0)).isEqualTo(view);
        assertThat(chosen(after, 0)).isEqualTo(chosen(before, 0));

        assertThat(view.bidding()).isTrue();
        assertThat(view.hand()).isEqualTo(mine);
        assertThat(view.ownBid()).isEqualTo(PlayerState.NO_BID);
        assertThat(view.currentTrick()).isEmpty();
        assertThat(view.seenCards()).as("입찰 단계엔 아직 공개된 카드가 없다").isEmpty();
        assertThat(view.unseenCards()).hasSize(Deck.SIZE - mine.size());
    }

    @Test
    void playing_view_hides_other_hands() {
        // 라운드 3 · 4인. 트릭 1 은 좌석 1 이 가져갔고(초록 10), 트릭 2 는 좌석 1 리드로
        // 세 장이 나와 이제 좌석 0 차례다.
        TrickResult first = resolved(
                PlayedCard.of(0, green(3)), PlayedCard.of(1, green(10)),
                PlayedCard.of(2, green(5)), PlayedCard.of(3, yellow(2)));
        TrickState second = new TrickState(1, List.of(
                PlayedCard.of(1, purple(4)), PlayedCard.of(2, purple(9)),
                PlayedCard.of(3, SkullCard.escape())));

        List<SkullCard> mine = List.of(purple(11), SkullCard.pirate());
        SkullKingState.Playing before = playing(mine,
                List.of(SkullCard.mermaid()), List.of(black(13)), first, second);
        SkullKingState.Playing after = playing(mine,
                List.of(black(13)), List.of(SkullCard.mermaid()), first, second);

        SkullKingBotView view = SkullKingBotView.of(before, 0);
        assertThat(SkullKingBotView.of(after, 0)).isEqualTo(view);
        assertThat(chosen(after, 0)).isEqualTo(chosen(before, 0));

        assertThat(view.bidding()).isFalse();
        assertThat(view.ownBid()).isEqualTo(1);
        assertThat(view.ownTricksWon()).isZero();
        assertThat(view.currentTrick()).isEqualTo(second.played());
        assertThat(view.playersAfterMe()).as("좌석 0 이 트릭의 마지막 순번").isZero();
    }

    @Test
    void playing_view_counts_seen_and_unseen_cards() {
        // 라운드 4 · 3인. 해적 5장 중 4장이 공개됐고 마지막 1장은 내 손에 있다.
        TrickResult first = resolved(
                PlayedCard.of(0, SkullCard.pirate()), PlayedCard.of(1, SkullCard.pirate()),
                PlayedCard.of(2, green(2)));
        TrickResult second = resolved(
                PlayedCard.of(0, SkullCard.pirate()), PlayedCard.of(1, SkullCard.escape()),
                PlayedCard.of(2, yellow(5)));
        TrickState current = new TrickState(0, List.of(PlayedCard.of(0, SkullCard.pirate())));

        List<SkullCard> mine = List.of(SkullCard.pirate(), black(1));
        SkullKingState.Playing state = new SkullKingState.Playing(4, List.of(
                new PlayerState(0, List.of(green(7)), 3, List.of(first, second)),
                new PlayerState(1, mine, 1, List.of()),
                new PlayerState(2, List.of(purple(6), SkullCard.mermaid()), 0, List.of())),
                0, current);

        SkullKingBotView view = SkullKingBotView.of(state, 1);

        List<SkullCard> expectedSeen = new ArrayList<>();
        first.cards().forEach(pc -> expectedSeen.add(pc.card()));
        second.cards().forEach(pc -> expectedSeen.add(pc.card()));
        expectedSeen.add(SkullCard.pirate());
        assertThat(view.seenCards()).containsExactlyInAnyOrderElementsOf(expectedSeen);

        List<SkullCard> unseen = view.unseenCards();
        assertThat(unseen).hasSize(Deck.SIZE - mine.size() - expectedSeen.size());
        assertThat(unseen)
                .as("해적 5장 = 공개 4 + 내 손 1 → multiset 차로 정확히 0장")
                .doesNotContain(SkullCard.pirate());
        assertThat(unseen.stream().filter(c -> c.equals(SkullCard.escape())).count())
                .as("탈출 5장 중 1장만 공개")
                .isEqualTo(4);
        assertThat(unseen)
                .as("남의 손패는 미공개로 남는다")
                .contains(green(7), purple(6), SkullCard.mermaid());
        assertThat(view.playersAfterMe()).isEqualTo(1);
    }

    /** 좌석 0 차례의 Playing 상태 — 좌석 1·2 의 남은 손패만 바꿔 끼운다. */
    private static SkullKingState.Playing playing(List<SkullCard> mine,
                                                  List<SkullCard> seat1,
                                                  List<SkullCard> seat2,
                                                  TrickResult first,
                                                  TrickState current) {
        return new SkullKingState.Playing(3, List.of(
                new PlayerState(0, mine, 1, List.of()),
                new PlayerState(1, seat1, 2, List.of(first)),
                new PlayerState(2, seat2, 0, List.of()),
                new PlayerState(3, List.of(green(1)), 0, List.of())),
                0, current);
    }
}
