package com.mirboard.domain.game.dambaduk.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.DambadukGameDefinition;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import java.security.SecureRandom;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 담바둑 판을 시작한다 (룰 §2·§3) — 선(흑) 좌석 추첨. 시작 이벤트는 방송하지 않는다 — 클라는 게임판에
 * 들어오며 resync 로 상태를 받는다. 저장 뒤 봇 루프와 턴 타이머를 건다.
 */
@Component
public class DambadukRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(DambadukRoundStarter.class);

    private final DambadukStateStore stateStore;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final DambadukEngine engine = new DambadukEngine();

    @Autowired
    public DambadukRoundStarter(DambadukStateStore stateStore,
                                @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 추첨). */
    public DambadukRoundStarter(DambadukStateStore stateStore,
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
        if (!DambadukGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < 2 || seatCount > 3) {
            // 정원·시작 인원이 2~3 이라 오면 안 된다 — 판을 만들지 않고 드러낸다.
            log.error("Dambaduk needs 2~3 players, got {} — skipping room={}", seatCount, event.roomId());
            return;
        }
        int firstSeat = random.nextInt(seatCount);
        DambadukState state = engine.startMatch(seatCount, firstSeat);
        stateStore.save(event.roomId(), state);

        log.info("Dambaduk match started: room={} seats={} firstSeat={}", event.roomId(), seatCount, firstSeat);
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
