package com.mirboard.domain.game.skullking.lifecycle;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 스컬킹 매치·1라운드를 초기화한다 (D-102). 이후 라운드는
 * 어댑터의 {@code advance} 가 {@code startRoundAndDrain} 으로 잇는다.
 *
 * <p>1라운드 첫 리드는 균일 무작위다 — 원문이 "적당한 방법으로 정한다"뿐이라 재현 가능성을
 * 위해 선택 결과를 로그로 남긴다 (§13-⑯).
 */
@Component
public class SkullKingRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(SkullKingRoundStarter.class);

    private final SkullKingStateStore stateStore;
    private final SkullKingMatchStateStore matchStateStore;
    private final SecureRandom random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    @Autowired
    public SkullKingRoundStarter(SkullKingStateStore stateStore,
                                 SkullKingMatchStateStore matchStateStore,
                                 @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                 @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, matchStateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 셔플/시작 좌석). */
    public SkullKingRoundStarter(SkullKingStateStore stateStore,
                                 SkullKingMatchStateStore matchStateStore,
                                 SecureRandom random,
                                 com.mirboard.infra.bot.BotScheduler botScheduler,
                                 com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.matchStateStore = matchStateStore;
        this.random = random;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!SkullKingGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < 2 || seatCount > 8) {
            log.warn("SkullKing needs 2~8 players, got {} — skipping room={}",
                    seatCount, event.roomId());
            return;
        }

        int firstStartSeat = random.nextInt(seatCount);
        SkullKingMatchState match = SkullKingMatchState.initial(seatCount, firstStartSeat);
        matchStateStore.save(event.roomId(), match);

        // 순수 엔진으로 1라운드 분배 — 첫 라운드에는 유령이 없어 드레인 불필요.
        SkullKingEngine engine = new SkullKingEngine(
                new GameContext(event.roomId(), event.playerIds()));
        SkullKingEngine.Result started = engine.startRound(match, random);
        stateStore.save(event.roomId(), started.newState());

        log.info("SkullKing match started: room={} seats={} firstStartSeat={} (§13-⑯ 무작위)",
                event.roomId(), seatCount, firstStartSeat);
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
