package com.mirboard.infra.ws;

import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.event.TichuMatchCompleted;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.scoring.RoundScore;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.messaging.DomainEventBus;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 9C — RoundEnd 도달 후 매치 진행 / 종료 처리. GameStompController 와
 * BotScheduler 모두 같은 후속 절차 (점수 누적, 다음 라운드 자동 시작 또는 매치 종료
 * 이벤트 발행, 룸 finished 마킹) 를 따르도록 공유 서비스로 추출.
 *
 * <p>호출자는 RoomActionLock 을 이미 보유하고 있어야 한다 — 본 서비스는 락을 직접
 * 다루지 않는다.
 */
@Service
public class MatchProgressService {

    private static final Logger log = LoggerFactory.getLogger(MatchProgressService.class);

    private final TichuMatchStateStore matchStateStore;
    private final TichuRoundStarter roundStarter;
    private final RoomService roomService;
    private final DomainEventBus events;
    private final MirboardMetrics metrics;

    public MatchProgressService(TichuMatchStateStore matchStateStore,
                                TichuRoundStarter roundStarter,
                                RoomService roomService,
                                DomainEventBus events,
                                MirboardMetrics metrics) {
        this.matchStateStore = matchStateStore;
        this.roundStarter = roundStarter;
        this.roomService = roomService;
        this.events = events;
        this.metrics = metrics;
    }

    /**
     * RoundEnd 상태 직후 호출. 점수 누적 + 매치 종료 / 다음 라운드 시작 분기.
     * `outbound` 리스트에 MatchEnded 또는 RoundStarted 이벤트를 in-place 로 추가.
     */
    public void onRoundEnd(String roomId,
                           Room room,
                           TichuState.RoundEnd ended,
                           List<TichuEvent> outbound) {
        TichuMatchState matchState = matchStateStore.load(roomId)
                .orElseGet(() -> TichuMatchState.initial(room.playerIds()));

        RoundScore lastScore = outbound.stream()
                .filter(TichuEvent.RoundEnded.class::isInstance)
                .map(TichuEvent.RoundEnded.class::cast)
                .map(TichuEvent.RoundEnded::score)
                .findFirst()
                .orElseGet(() -> new RoundScore(ended.teamAScore(), ended.teamBScore(), -1, false));

        TichuMatchState afterRound = matchState.withRoundCompleted(lastScore);
        matchStateStore.save(roomId, afterRound);

        metrics.roundCompleted();
        log.info("Round completed: round={} A={} B={} cumulativeA={} cumulativeB={}",
                afterRound.roundNumber() - 1, lastScore.teamAScore(), lastScore.teamBScore(),
                afterRound.cumulativeA(), afterRound.cumulativeB());

        if (afterRound.isMatchOver()) {
            outbound.add(new TichuEvent.MatchEnded(
                    afterRound.winningTeam(),
                    afterRound.scoresByTeam(),
                    afterRound.roundScores().size()));
            events.publish(new TichuMatchCompleted(
                    roomId,
                    room.playerIds(),
                    afterRound.cumulativeA(),
                    afterRound.cumulativeB(),
                    afterRound.winningTeam(),
                    afterRound.roundScores()));
            metrics.matchCompleted();
            log.info("Match ended: winner={} rounds={} A={} B={}",
                    afterRound.winningTeam(), afterRound.roundScores().size(),
                    afterRound.cumulativeA(), afterRound.cumulativeB());
            try {
                roomService.markFinished(roomId);
            } catch (RuntimeException e) {
                log.warn("Failed to mark room {} finished: {}", roomId, e.getMessage());
            }
            return;
        }
        // 매치 계속 — 다음 라운드 Dealing(8) 생성 + RoundStarted 알림.
        roundStarter.startRound(roomId, room.playerIds(), afterRound.roundNumber());
        outbound.add(new TichuEvent.RoundStarted(
                afterRound.roundNumber(), afterRound.scoresByTeam()));
    }
}
