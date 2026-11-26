package com.mirboard.domain.game.wordtemp.lifecycle;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.WordTempConfig;
import com.mirboard.domain.game.wordtemp.WordTempEngine;
import com.mirboard.domain.game.wordtemp.WordTempGameDefinition;
import com.mirboard.domain.game.wordtemp.WordTempSettings;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
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
 * <p><b>저장한 뒤에 {@code WORD_SELECT_STARTED} 를 방송한다(D-319).</b> 방이 IN_GAME 이 됐다는 소식은 이 리스너보다 먼저 나가서, 게임판에
 * 막 들어온 클라의 첫 resync 가 상태 저장보다 빠를 수 있다(그때는 "아직 없음"). 이 게임은 첫 단계가 20초짜리 낱말 고르기라 다음 공개
 * 이벤트를 기다리면 첫 낱말을 고를 수 없다 — 그래서 상태를 쓴 <b>다음에</b> 공개 이벤트 하나를 보내, 빈손인 클라가 그것을 받고 다시 resync
 * 하게 한다(그때는 상태가 반드시 있다). 내 후보는 그 resync 의 비공개 뷰로 간다 — 시작 때 비공개 이벤트는 보내지 않는다(여기서는 봇 좌석을
 * 몰라 "봇에게는 보내지 않는다"를 지킬 수 없다). 그 뒤의 라운드부터는 엔진이 {@code WORD_SELECT_STARTED}·{@code WORD_CANDIDATES} 를 낸다.
 * 저장·방송 뒤 턴·엔진 타이머를 건다(낱말 고르기 마감).
 */
@Component
public class WordTempRoundStarter {

    private static final Logger log = LoggerFactory.getLogger(WordTempRoundStarter.class);

    private final WordTempStateStore stateStore;
    private final WordTempWords words;
    private final Clock clock;
    private final com.mirboard.infra.bot.BotScheduler botScheduler;
    private final com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout;
    private final com.mirboard.infra.ws.GameEventBroadcaster broadcaster;
    private final SecureRandom random = new SecureRandom();

    public WordTempRoundStarter(WordTempStateStore stateStore,
                                WordTempWords words,
                                Clock clock,
                                @Lazy com.mirboard.infra.bot.BotScheduler botScheduler,
                                @Lazy com.mirboard.infra.bot.TurnTimeoutScheduler turnTimeout,
                                @Lazy com.mirboard.infra.ws.GameEventBroadcaster broadcaster) {
        this.stateStore = stateStore;
        this.words = words;
        this.clock = clock;
        this.botScheduler = botScheduler;
        this.turnTimeout = turnTimeout;
        this.broadcaster = broadcaster;
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
        WordTempEngine.Result started = engine.start(candidates, seed, clock.millis());
        stateStore.save(event.roomId(), started.newState());
        // 저장 → 방송 순서를 바꾸지 말 것: 이 이벤트를 받은 클라의 resync 는 반드시 저장된 상태를 본다. 공개 이벤트만 보낸다(위 주석).
        List<WordTempEvent> announce = started.events().stream().filter(e -> !e.isPrivate()).toList();
        try {
            broadcaster.broadcast(event.roomId(), announce, event.playerIds());
        } catch (RuntimeException e) {
            // 방송 실패가 판 시작(타이머 무장)을 막지 않게 — 클라는 늦어도 다음 공개 이벤트에서 판을 받는다.
            log.error("Word temperature start announce failed: room={}", event.roomId(), e);
        }

        log.info("Word temperature match started: room={} seats={} rounds={} table={}", event.roomId(), seatCount,
                rounds, words.table().version());
        botScheduler.scheduleBots(event.roomId());
        turnTimeout.onTurnAdvanced(event.roomId());
    }
}
