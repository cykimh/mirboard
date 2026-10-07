package com.mirboard.infra.ws;

import static com.mirboard.domain.game.skullking.state.MatchStateFixtures.scored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.SkullKingGameEngine;
import com.mirboard.domain.game.skullking.event.SkullKingEvent;
import com.mirboard.domain.game.skullking.persistence.SkullKingMatchStateStore;
import com.mirboard.domain.game.skullking.persistence.SkullKingStateStore;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.testsupport.LogCapture;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-131 — 스컬킹 매치 기록이 실패해도(동기 {@code @Transactional} 리스너 {@code SkullKingMatchRecorder} 의 DB 장애) 매치는
 * 끝난다: 마지막 이벤트({@code ROUND_ENDED}·{@code MATCH_ENDED})가 나갈 묶음에 남고, 방은 FINISHED 로 간다. 예전에는 예외가
 * {@code recordIfEnded} 에서 진행 경로로 새어 호출자(컨트롤러·봇·타이머·탈주)가 저장 뒤의 방송과 {@code markFinished} 를
 * 건너뛰었다 — 방이 IN_GAME 에 남고(끝난 매치라 진행 킥 대상도 아니다) 클라는 직전 화면에 멈췄다. 원카드(D-130,
 * {@link OneCardRecorderFailureTest})와 같은 격리다. 기록 리스너는 하나뿐이라 발행 지점에서 잡아도 건너뛸 다른 리스너가 없다.
 */
class SkullKingRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final List<Long> PLAYERS = List.of(10L, 20L, 30L);

    private final SkullKingStateStore states = mock(SkullKingStateStore.class);
    private final SkullKingMatchStateStore matchStates = mock(SkullKingMatchStateStore.class);
    private final ApplicationEventPublisher failingRecorder = event -> {
        throw new DataAccessResourceFailureException("simulated DB outage in SkullKingMatchRecorder");
    };
    private final SkullKingGameEngine engine = new SkullKingGameEngine(
            new GameContext(ROOM, PLAYERS), states, matchStates, new SecureRandom(), failingRecorder);

    private static final Map<Integer, RoundScore> LAST_ROUND = Map.of(
            0, new RoundScore(1, 1, 20, 0),
            1, new RoundScore(0, 1, -10, 0),
            2, new RoundScore(0, 0, 10, 0));

    @Test
    void the_final_round_still_ends_the_match_and_finishes_the_room_when_recording_fails() {
        SkullKingMatchState beforeLastRound = SkullKingMatchState.initial(3, 0);
        for (int i = 0; i < SkullKingMatchState.TOTAL_ROUNDS - 1; i++) {
            beforeLastRound = scored(beforeLastRound, Map.of(0, 10, 1, 20, 2, 0), 3);
        }
        when(matchStates.load(ROOM)).thenReturn(Optional.of(beforeLastRound));
        RoomService roomService = mock(RoomService.class);
        GameRegistry games = mock(GameRegistry.class);
        // 스컬킹은 리매치를 선언하지 않는다(기본 false) — 끝나면 방이 FINISHED 로 간다.
        when(games.require(anyString())).thenReturn(mock(GameDefinition.class));
        MatchProgressService progress = new MatchProgressService(roomService, mock(MirboardMetrics.class), games);
        List<GameEvent> outbound = new ArrayList<>();

        try (LogCapture logs = LogCapture.of(SkullKingGameEngine.class)) {
            assertThatCode(() -> progress.advance(engine, room(), roundEnd(SkullKingMatchState.TOTAL_ROUNDS), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound).anyMatch(SkullKingEvent.RoundEnded.class::isInstance);
            assertThat(outbound.getLast()).isInstanceOf(SkullKingEvent.MatchEnded.class);
            verify(matchStates).save(eq(ROOM), argThat(SkullKingMatchState::isMatchOver));
            verify(roomService).markFinished(ROOM);
            assertRecordFailureLogged(logs);
        }
    }

    /** 탈주로 한 명만 남아 끝나는 매치 — 탈주 경로도 같은 발행 지점을 탄다. 포트 답은 MATCH_ENDED 그대로다. */
    @Test
    void a_desertion_that_ends_the_match_still_answers_match_ended_when_recording_fails() {
        SkullKingEngine rules = new SkullKingEngine(new GameContext(ROOM, PLAYERS));
        SkullKingMatchState match = SkullKingMatchState.initial(3, 0);
        SkullKingState state = rules.startRound(match, new Random(7)).newState();
        SkullKingEngine.Desertion first = rules.desert(state, match, 0, Set.of(0, 1, 2));
        when(states.load(ROOM)).thenReturn(Optional.of(first.newState()));
        when(matchStates.load(ROOM)).thenReturn(Optional.of(first.matchState()));
        List<GameEvent> outbound = new ArrayList<>();

        try (LogCapture logs = LogCapture.of(SkullKingGameEngine.class)) {
            GameEngine.DesertOutcome outcome = engine.desert(1, 20L, outbound);

            assertThat(outcome).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
            assertThat(outbound).anyMatch(SkullKingEvent.MatchEnded.class::isInstance);
            verify(matchStates).save(eq(ROOM), argThat(SkullKingMatchState::isMatchOver));
            assertRecordFailureLogged(logs);
        }
    }

    /** 결과를 실은 ERROR 에 기록 예외의 스택도 붙는다 — 문장만 보면 스택을 빼도({@code err={}}) 통과했다. */
    private static void assertRecordFailureLogged(LogCapture logs) {
        assertThat(logs.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("SkullKing match record failed").contains("room=" + ROOM);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
        });
    }

    private static Room room() {
        return new Room(ROOM, "방", "SKULL_KING", 10L, RoomStatus.IN_GAME, 3, 3, PLAYERS, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of());
    }

    private static SkullKingState.RoundEnd roundEnd(int round) {
        return new SkullKingState.RoundEnd(round, List.of(
                PlayerState.initial(0, List.of()),
                PlayerState.initial(1, List.of()),
                PlayerState.initial(2, List.of())), 0, LAST_ROUND);
    }
}
