package com.mirboard.domain.game.tichu.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.TurnManager;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Deck;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이되었을 때 첫 라운드를 초기화하고, 한 라운드가 종료된 직후
 * 매치가 계속되면 다음 라운드도 본 컴포넌트를 통해 새 Dealing(8) 상태가 만들어진다.
 * Phase 5b 의 분배 룰을 그대로 적용: 8장 visible + 6장 reserved.
 */
@Component
public class TichuRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(TichuRoundStarter.class);
    private static final int FULL_HAND = Deck.SIZE / TurnManager.SEATS;       // 14
    private static final int FIRST_DEAL_SIZE = 8;
    private static final int RESERVED_SIZE = FULL_HAND - FIRST_DEAL_SIZE;     // 6

    private final TichuGameStateStore stateStore;
    private final TichuMatchStateStore matchStateStore;
    private final SecureRandom random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;

    @Autowired
    public TichuRoundStarter(TichuGameStateStore stateStore,
                             TichuMatchStateStore matchStateStore,
                             @Lazy com.mirboard.infra.bot.BotScheduler botScheduler) {
        this(stateStore, matchStateStore, new SecureRandom(), botScheduler);
    }

    /** Test-only entry point (deterministic shuffle). */
    public TichuRoundStarter(TichuGameStateStore stateStore,
                             TichuMatchStateStore matchStateStore,
                             SecureRandom random,
                             com.mirboard.infra.bot.BotScheduler botScheduler) {
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.random = random;
        this.botScheduler = botScheduler;
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
        TichuMatchState matchState = TichuMatchState.initial(event.playerIds());
        matchStateStore.save(event.roomId(), matchState);
        startRound(event.roomId(), event.playerIds(), matchState.roundNumber());
    }

    /**
     * 새 라운드를 시작 — 8장 visible + 6장 reserved 분배 후 Dealing(8) 영속화.
     * 다음 라운드 진입 시 GameStompController 가 호출.
     */
    public void startRound(String roomId, List<Long> playerIds, int roundNumber) {
        Deck deck = Deck.shuffled(random);
        DealResult dealt = deal(deck.cards());

        List<PlayerState> players = new java.util.ArrayList<>(TurnManager.SEATS);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            players.add(PlayerState.initial(seat, dealt.visible().get(seat)));
        }

        TichuState.Dealing initial = new TichuState.Dealing(
                players, FIRST_DEAL_SIZE, Set.of(), dealt.reserved());

        stateStore.save(roomId, initial);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            stateStore.saveHand(roomId, playerIds.get(seat), dealt.visible().get(seat));
        }

        log.info("Tichu round {} started for room={} — Dealing(8), reserved 6 per seat",
                roundNumber, roomId);
        // Phase 9C — 솔로 방이면 봇 차례를 비동기로 처리. 일반 방이면 봇 자리가 없어 no-op.
        botScheduler.scheduleBots(roomId);
    }

    private static DealResult deal(List<Card> shuffled) {
        Map<Integer, List<Card>> visible = new LinkedHashMap<>(TurnManager.SEATS);
        Map<Integer, List<Card>> reserved = new HashMap<>(TurnManager.SEATS);
        for (int seat = 0; seat < TurnManager.SEATS; seat++) {
            int from = seat * FULL_HAND;
            int firstEnd = from + FIRST_DEAL_SIZE;
            int reservedEnd = firstEnd + RESERVED_SIZE;
            visible.put(seat, List.copyOf(shuffled.subList(from, firstEnd)));
            reserved.put(seat, List.copyOf(shuffled.subList(firstEnd, reservedEnd)));
        }
        return new DealResult(visible, reserved);
    }

    private record DealResult(Map<Integer, List<Card>> visible,
                              Map<Integer, List<Card>> reserved) {
    }
}
