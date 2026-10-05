package com.mirboard.domain.game.onecard.lifecycle;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.onecard.Dealer;
import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.OneCardState;
import java.security.SecureRandom;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 원카드 판을 시작한다 (D-128) — 분배·시작 카드·첫 차례(룰 §2·§3). 1판 =
 * 1매치라 이후 라운드가 없다.
 *
 * <p>스컬킹처럼 시작 이벤트는 브로드캐스트하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다.
 * 저장 뒤 봇 루프와 턴·엔진 타이머를 건다.
 */
@Component
public class OneCardRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(OneCardRoundStarter.class);

    private final OneCardStateStore stateStore;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    @Autowired
    public OneCardRoundStarter(OneCardStateStore stateStore,
                               @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                               @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 분배·첫 차례). */
    public OneCardRoundStarter(OneCardStateStore stateStore,
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
        if (!OneCardGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < Dealer.MIN_SEATS || seatCount > Dealer.MAX_SEATS) {
            log.warn("OneCard needs {}~{} players, got {} — skipping room={}",
                    Dealer.MIN_SEATS, Dealer.MAX_SEATS, seatCount, event.roomId());
            return;
        }

        // 시작에는 경쟁 창이 없어 창 설정이 필요 없다 — 분배와 첫 차례만 난수를 쓴다.
        OneCardEngine engine = new OneCardEngine(new GameContext(event.roomId(), event.playerIds()), random);
        OneCardState state = engine.startMatch().newState();
        stateStore.save(event.roomId(), state);

        log.info("OneCard match started: room={} seats={} firstSeat={} startCard={}",
                event.roomId(), seatCount, state.turnSeat(), state.topCard());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
