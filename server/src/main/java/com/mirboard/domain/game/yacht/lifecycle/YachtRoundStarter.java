package com.mirboard.domain.game.yacht.lifecycle;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.yacht.YachtEngine;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 요트 판을 시작한다 (S3) — 첫 라운드, 좌석 0 차례(룰 §3). 1판 = 1매치.
 *
 * <p>시작 이벤트는 브로드캐스트하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다(원카드와 같다). 저장 뒤 봇 루프와
 * 턴·엔진 타이머를 건다 — 차례 시작 시각을 지금으로 기록하므로 첫 차례의 전체 제한도 여기서 센다.
 */
@Component
public class YachtRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(YachtRoundStarter.class);

    private final YachtStateStore stateStore;
    private final Clock clock;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;

    public YachtRoundStarter(YachtStateStore stateStore,
                             Clock clock,
                             @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                             @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!YachtGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < 1 || seatCount > 4) {
            log.warn("Yacht needs 1~4 players, got {} — skipping room={}", seatCount, event.roomId());
            return;
        }
        // 시작에는 차례 제한·봇 좌석이 필요 없다 — 시작 시각만 쓴다(마감은 어댑터가 방 설정으로 계산). 진행 방식(D-202)은 옵션으로.
        YachtEngine engine = new YachtEngine(new GameContext(event.roomId(), event.playerIds(), 0, 0, List.of(),
                event.options()));
        YachtState state = engine.start(clock.millis()).newState();
        stateStore.save(event.roomId(), state);

        log.info("Yacht match started: room={} seats={} simultaneous={}", event.roomId(), seatCount, state.simultaneous());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
