package com.mirboard.domain.game.cloudroute.lifecycle;

import com.mirboard.domain.game.cloudroute.CloudRouteEngine;
import com.mirboard.domain.game.cloudroute.CloudRouteGameDefinition;
import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStartingEvent;
import java.security.SecureRandom;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 구름항로 판을 시작한다 (D-212) — 시작 현금·편지 섞기·첫 차례(룰 §3). 1판 = 1매치라 이후 라운드
 * 시작이 없다. 원카드처럼 시작 이벤트는 방송하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다. 저장 뒤 봇 루프와 턴
 * 타이머를 건다.
 */
@Component
public class CloudRouteRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(CloudRouteRoundStarter.class);
    static final int MIN_SEATS = 2;
    static final int MAX_SEATS = 4;

    private final CloudRouteStateStore stateStore;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    @Autowired
    public CloudRouteRoundStarter(CloudRouteStateStore stateStore,
                                  @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                  @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 섞기·첫 차례). */
    public CloudRouteRoundStarter(CloudRouteStateStore stateStore,
                                  Random random,
                                  com.mirboard.infra.bot.BotScheduler botScheduler,
                                  com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.random = random;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!CloudRouteGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < MIN_SEATS || seatCount > MAX_SEATS) {
            log.warn("CloudRoute needs {}~{} players, got {} — skipping room={}",
                    MIN_SEATS, MAX_SEATS, seatCount, event.roomId());
            return;
        }
        // 봇 좌석은 시작 상태에 영향이 없다(종료 판정 NO_HUMANS 는 진행 중 어댑터의 GameContext 가 맡는다).
        CloudRouteEngine engine = new CloudRouteEngine(new GameContext(event.roomId(), event.playerIds()), random);
        CloudRouteState state = engine.startMatch().newState();
        stateStore.save(event.roomId(), state);

        log.info("CloudRoute match started: room={} seats={} firstSeat={} roundLimit={}",
                event.roomId(), seatCount, state.turnSeat(), state.roundLimit());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
