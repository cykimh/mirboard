package com.mirboard.domain.game.starsquare.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.starsquare.StarSquareConfig;
import com.mirboard.domain.game.starsquare.StarSquareEngine;
import com.mirboard.domain.game.starsquare.StarSquareGameDefinition;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.persistence.StarSquarePuzzles;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.puzzle.PuzzlePicker;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 별자리 수판 판을 시작한다(S2) — 퍼즐 R개를 은행에서 한꺼번에 고르고(서버 난수, 같은 원본 겹치지 않음 —
 * 룰 §3-3·§4.2) 준비 3초 단계로 둔다. 1판 = 1매치.
 *
 * <p>시작 이벤트는 브로드캐스트하지 않는다 — 클라는 게임판에 들어오며 resync 로 상태를 받고, 첫 퍼즐은 준비가 끝난 엔진 타이머의
 * {@code PUZZLE_STARTED} 로 모두에게 동시에 간다. 저장 뒤 턴·엔진 타이머를 건다(준비 끝 시각).
 */
@Component
public class StarSquareRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(StarSquareRoundStarter.class);

    private final StarSquareStateStore stateStore;
    private final StarSquarePuzzles puzzles;
    private final Clock clock;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final SecureRandom random = new SecureRandom();

    public StarSquareRoundStarter(StarSquareStateStore stateStore,
                                  StarSquarePuzzles puzzles,
                                  Clock clock,
                                  @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                  @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.puzzles = puzzles;
        this.clock = clock;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!StarSquareGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < 1 || seatCount > 4) {
            log.warn("Star square needs 1~4 players, got {} — skipping room={}", seatCount, event.roomId());
            return;
        }
        // 시작에는 좌석 수·퍼즐 수만 쓴다(봇 좌석·제한·배율은 어댑터가 방 설정으로 — 봇 계획은 첫 퍼즐이 열릴 때 만든다)
        Difficulty difficulty = Difficulty.fromOption(event.option(RoomOption.DIFFICULTY));
        int count = StarSquareConfig.puzzleCountFor(event.option(RoomOption.MATCH_LENGTH));
        StarSquareEngine engine = new StarSquareEngine(new StarSquareConfig(seatCount, Set.of(), count, difficulty,
                BotLevel.NORMAL, 0));
        List<PuzzleSpec> picked = PuzzlePicker.pick(puzzles.bank(), difficulty, count, random);
        StarSquareState state = engine.start(picked, random.nextLong(), clock.millis()).newState();
        stateStore.save(event.roomId(), state);

        log.info("Star square match started: room={} seats={} difficulty={} puzzles={}", event.roomId(), seatCount,
                difficulty, count);
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
