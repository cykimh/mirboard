package com.mirboard.domain.game.onecard.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-129 — 원카드 손패 이벤트는 방 순번을 쓰지 않는다. 쓰면 그 이벤트를 받지 않는 좌석에는 다음 공개 이벤트가 구멍이
 * 되어, 내거나 먹을 때마다 전원이 resync 했다. 공개 이벤트는 그대로 순번을 쓴다 — 클라는 공개 이벤트의 순번으로 중복과
 * 구멍을 판정한다(D-124).
 */
class OneCardEventSequencingTest {

    private static final PlayingCard HEART_5 = PlayingCard.of(Suit.HEART, 5);

    @Test
    void hand_events_are_private_and_unsequenced() {
        var dealt = new OneCardEvent.HandDealt(0, List.of(HEART_5), 1);
        var updated = new OneCardEvent.HandUpdated(1, List.of(HEART_5), List.of(HEART_5), 2);

        assertThat(dealt.isPrivate()).isTrue();
        assertThat(dealt.sequenced()).isFalse();
        assertThat(updated.isPrivate()).isTrue();
        assertThat(updated.sequenced()).isFalse();
    }

    @Test
    void public_events_stay_sequenced() {
        List<OneCardEvent> publicEvents = List.of(
                new OneCardEvent.MatchStarted(0, HEART_5, 7, 39),
                new OneCardEvent.CardPlayed(0, HEART_5, null, 6, 0, 1),
                new OneCardEvent.CardsDrawn(1, 2, OneCardEvent.DrawReason.ATTACK, 9, 30),
                new OneCardEvent.PileReshuffled(40),
                new OneCardEvent.TurnChanged(1, 1, 0),
                new OneCardEvent.RaceOpened(7, 0, 3, -20, 40, 3000),
                new OneCardEvent.RaceResolved(7, OneCardEvent.RaceOutcome.CALLED, 0),
                new OneCardEvent.PlayerEliminated(2, Elimination.Reason.BANKRUPT, 20, 25),
                new OneCardEvent.MatchEnded(MatchResult.EndReason.FINISHED, List.of(
                        new MatchResult.Standing(0, 1, 0, MatchResult.SeatStatus.FINISHED))));

        assertThat(publicEvents).hasSize(9).allSatisfy(e -> {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
        });
    }
}
