package com.mirboard.domain.game.artillery.lifecycle;

import com.mirboard.domain.game.artillery.ArtilleryEngine;
import com.mirboard.domain.game.artillery.ArtilleryGameDefinition;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 씨앗포 판을 시작한다 (S2, 룰 §9.1) — 매치 시드를 뽑아 맵·출발 자리·첫 차례를 만든다. 1판 = 1매치.
 *
 * <p>시작 이벤트는 브로드캐스트하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다(원카드와 같다). 봇 좌석은 상태에 둔다
 * (사람이 모두 탈락하면 끝 — §9.3 NO_HUMANS). 저장 뒤 봇 루프와 턴 타이머를 건다.
 */
@Component
public class ArtilleryRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(ArtilleryRoundStarter.class);

    private final ArtilleryStateStore stateStore;
    private final ArtilleryGameDefinition definition;
    private final BotUserRegistry bots;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    @Autowired
    public ArtilleryRoundStarter(ArtilleryStateStore stateStore,
                                 ArtilleryGameDefinition definition,
                                 BotUserRegistry bots,
                                 @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                 @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, definition, bots, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 시드). */
    public ArtilleryRoundStarter(ArtilleryStateStore stateStore,
                                 ArtilleryGameDefinition definition,
                                 BotUserRegistry bots,
                                 Random random,
                                 com.mirboard.infra.bot.BotScheduler botScheduler,
                                 com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.definition = definition;
        this.bots = bots;
        this.random = random;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!ArtilleryGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < ArtilleryEngine.MIN_PLAYERS || seatCount > ArtilleryEngine.MAX_PLAYERS) {
            log.warn("Artillery needs {}~{} players, got {} — skipping room={}",
                    ArtilleryEngine.MIN_PLAYERS, ArtilleryEngine.MAX_PLAYERS, seatCount, event.roomId());
            return;
        }
        List<Integer> botSeats = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            if (bots.isBot(event.playerIds().get(seat))) {
                botSeats.add(seat);
            }
        }
        long seed = random.nextLong();
        ArtilleryState state = definition.rules().startMatch(seed, seatCount, botSeats).newState();
        stateStore.save(event.roomId(), state);

        log.info("Artillery match started: room={} seats={} bots={} firstSeat={}",
                event.roomId(), seatCount, botSeats, state.turnSeat());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
