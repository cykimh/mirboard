package com.mirboard.domain.game.tichu.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.state.TichuDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-126 — 티츄 비공개 이벤트는 방 순번을 쓰지 않는다. 예전에는 카드를 낼 때마다 {@code HAND_DEALT}
 * 가 순번을 하나 써서 다음 공개 이벤트가 모든 클라에서 구멍이 됐다(매 플레이 전원 resync).
 */
class TichuEventSequencingTest {

    @Test
    void private_events_are_unsequenced() {
        var handDealt = new TichuEvent.HandDealt(0, List.of(Card.normal(Suit.JADE, 5)), 1);
        var received = new TichuEvent.CardsReceived(1, List.of(
                new TichuEvent.ReceivedCard(Card.normal(Suit.STAR, 9), 0)));

        assertThat(handDealt.isPrivate()).isTrue();
        assertThat(handDealt.sequenced()).isFalse();
        assertThat(received.isPrivate()).isTrue();
        assertThat(received.sequenced()).isFalse();
    }

    @Test
    void public_events_stay_sequenced() {
        List<TichuEvent> publicEvents = List.of(
                new TichuEvent.Passed(0),
                new TichuEvent.TurnChanged(1),
                new TichuEvent.TrickTaken(1, 10),
                new TichuEvent.TichuDeclared(2, TichuDeclaration.TICHU),
                new TichuEvent.WishMade(7),
                new TichuEvent.WishCleared(7),
                new TichuEvent.PlayerFinished(3, 1));

        assertThat(publicEvents).allSatisfy(e -> {
            assertThat(e.isPrivate()).isFalse();
            assertThat(e.sequenced()).isTrue();
        });
    }
}
