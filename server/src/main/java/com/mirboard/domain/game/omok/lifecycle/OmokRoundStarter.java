package com.mirboard.domain.game.omok.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokGameDefinition;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokSettings;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.state.OmokState;
import java.security.SecureRandom;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 오목 판을 시작한다 (룰 §3) — 색 추첨(흑 팀)과 첫 수 좌석 추첨(2:2 는 흑 팀 두 사람 중). 모드·룰은
 * 시작 이벤트의 방 설정(D-176)에서 읽는다. 시작 이벤트는 방송하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받는다.
 * 저장 뒤 봇 루프와 턴 타이머를 건다.
 */
@Component
public class OmokRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(OmokRoundStarter.class);

    private final OmokStateStore stateStore;
    private final Random random;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final OmokEngine engine = new OmokEngine();

    @Autowired
    public OmokRoundStarter(OmokStateStore stateStore,
                            @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                            @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this(stateStore, new SecureRandom(), botScheduler, turnTimeout);
    }

    /** 테스트 전용 진입점 (결정적 추첨). */
    public OmokRoundStarter(OmokStateStore stateStore,
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
        if (!OmokGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        OmokSettings settings = OmokSettings.from(event.options());
        OmokMode mode = settings.mode();
        int seatCount = event.playerIds().size();
        if (seatCount != mode.seatCount()) {
            // 정원·시작 인원을 모드가 정하므로(D-176) 오면 안 된다 — 판을 만들지 않고 드러낸다.
            log.error("Omok {} needs {} players, got {} — skipping room={}",
                    mode, mode.seatCount(), seatCount, event.roomId());
            return;
        }
        int blackTeam = random.nextInt(2);
        int firstSeat = mode == OmokMode.SOLO ? blackTeam : blackTeam + 2 * random.nextInt(2);
        OmokState state = engine.startMatch(mode, settings.rule(), seatCount, blackTeam, firstSeat);
        stateStore.save(event.roomId(), state);

        log.info("Omok match started: room={} mode={} rule={} blackTeam={} firstSeat={}",
                event.roomId(), mode, settings.rule(), blackTeam, firstSeat);
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
