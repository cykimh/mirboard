package com.mirboard.domain.game.tichu.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.tichu.TurnManager;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 막 IN_GAME 으로 전이되었을 때 본 컴포넌트가 라운드를 초기화한다 — Dealing 단계
 * (8장 → Grand Tichu, 14장 → Tichu, 카드 패스) 는 MVP 단순화를 위해 스킵하고 Playing
 * 단계로 바로 진입. Mahjong 보유자가 첫 트릭의 리드.
 */
@Component
public class TichuRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(TichuRoundStarter.class);
    private static final int CARDS_PER_PLAYER = Deck.SIZE / TurnManager.SEATS;  // 14

    private final TichuGameStateStore stateStore;
    private final SecureRandom random;

    public TichuRoundStarter(TichuGameStateStore stateStore) {
        this(stateStore, new SecureRandom());
    }

    /** Test-only entry point (deterministic shuffle). */
    public TichuRoundStarter(TichuGameStateStore stateStore, SecureRandom random) {
        this.stateStore = stateStore;
        this.random = random;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!TichuGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        if (event.playerIds().size() != TurnManager.SEATS) {
            log.warn("Tichu round needs 4 players, got {} — skipping", event.playerIds().size());
            return;
        }

        Deck deck = Deck.shuffled(random);
        List<List<Card>> hands = deal(deck.cards());

        List<PlayerState> players = new ArrayList<>(TurnManager.SEATS);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            players.add(PlayerState.initial(seat, hands.get(seat)));
        }

        int leadSeat = mahjongHolderSeat(hands);
        TichuState.Playing initial = new TichuState.Playing(
                players, TrickState.lead(leadSeat, null), -1);

        stateStore.save(event.roomId(), initial);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            stateStore.saveHand(event.roomId(), event.playerIds().get(seat), hands.get(seat));
        }

        log.info("Tichu round started for room={}, leadSeat={}", event.roomId(), leadSeat);
    }

    private static List<List<Card>> deal(List<Card> shuffled) {
        List<List<Card>> hands = new ArrayList<>(TurnManager.SEATS);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            int from = seat * CARDS_PER_PLAYER;
            int to = from + CARDS_PER_PLAYER;
            hands.add(List.copyOf(shuffled.subList(from, to)));
        }
        return hands;
    }

    private static int mahjongHolderSeat(List<List<Card>> hands) {
        for (int seat = 0; seat < hands.size(); seat++) {
            if (hands.get(seat).stream().anyMatch(c -> c.is(Special.MAHJONG))) {
                return seat;
            }
        }
        throw new IllegalStateException("Mahjong not found in any hand — deck integrity broken");
    }
}
