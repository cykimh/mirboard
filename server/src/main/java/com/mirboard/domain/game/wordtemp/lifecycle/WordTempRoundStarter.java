package com.mirboard.domain.game.wordtemp.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.WordTempConfig;
import com.mirboard.domain.game.wordtemp.WordTempEngine;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.WordTempSettings;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.persistence.WordTempStateStore;
import com.mirboard.domain.game.wordtemp.rules.CandidateDealer;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 방이 IN_GAME 으로 전이됐을 때 낱말 온도 판을 시작한다(S2) — 정답 은행에서 좌석마다 후보 {@code max(3, R+1)}개를 나누고(한 사람의 후보는
 * 서로 다른 범주, 판 전체에서 겹치지 않음 — 룰 §3.1) 1라운드 낱말 고르기 단계로 둔다. 1판 = 1매치.
 *
 * <p>시작 이벤트는 브로드캐스트하지 않는다(다른 게임의 스타터와 같다) — 클라는 게임판에 들어오며 resync 로 공개 뷰({@code WORD_SELECT}·남은
 * 시간)와 비공개 뷰(내 후보)를 받는다. 그 뒤의 라운드부터는 {@code WORD_SELECT_STARTED}·{@code WORD_CANDIDATES} 가 이벤트로 간다.
 * 저장 뒤 턴·엔진 타이머를 건다(낱말 고르기 마감).
 */
@Component
public class WordTempRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(WordTempRoundStarter.class);

    private final WordTempStateStore stateStore;
    private final WordTempWords words;
    private final Clock clock;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final SecureRandom random = new SecureRandom();

    public WordTempRoundStarter(WordTempStateStore stateStore,
                                WordTempWords words,
                                Clock clock,
                                @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout) {
        this.stateStore = stateStore;
        this.words = words;
        this.clock = clock;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
    }

    @EventListener
    public void onGameStarting(GameStartingEvent event) {
        if (!WordTempGameDefinition.ID.equals(event.gameType())) {
            return;
        }
        int seatCount = event.playerIds().size();
        if (seatCount < WordTempConfig.MIN_SEATS || seatCount > WordTempConfig.MAX_SEATS) {
            log.warn("Word temperature needs 3~6 players, got {} — skipping room={}", seatCount, event.roomId());
            return;
        }
        // 시작에는 좌석 수·라운드 수만 쓴다(봇 좌석·힌트 제한·판 상한·금칙어는 어댑터가 방 설정으로 — 시작 이벤트는 방송하지 않는다)
        int rounds = WordTempSettings.roundsFor(event.option(RoomOption.MATCH_LENGTH));
        WordTempEngine engine = new WordTempEngine(WordTempConfig.of(seatCount, Set.of(), rounds,
                WordTempConfig.DEFAULT_HINT_SECONDS, WordTempBotLevel.NORMAL), words.table());
        long seed = random.nextLong();
        List<List<AnswerEntry>> candidates = CandidateDealer.deal(words.bank(), seatCount, rounds,
                new SplittableRandom(seed));
        WordTempState state = engine.start(candidates, seed, clock.millis()).newState();
        stateStore.save(event.roomId(), state);

        log.info("Word temperature match started: room={} seats={} rounds={} table={}", event.roomId(), seatCount,
                rounds, words.table().version());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
