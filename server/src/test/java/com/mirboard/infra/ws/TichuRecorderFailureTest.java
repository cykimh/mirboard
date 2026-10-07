package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.scoring.RatedMatchPolicy;
import com.mirboard.domain.game.scoring.UserGameStatsService;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameEngine;
import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.event.TichuEvent;
import com.mirboard.domain.game.tichu.lifecycle.TichuRoundStarter;
import com.mirboard.domain.game.tichu.persistence.MatchResultRecorder;
import com.mirboard.domain.game.tichu.persistence.TichuGameStateStore;
import com.mirboard.domain.game.tichu.persistence.TichuMatchParticipantRepository;
import com.mirboard.domain.game.tichu.persistence.TichuMatchResult;
import com.mirboard.domain.game.tichu.persistence.TichuMatchResultRepository;
import com.mirboard.domain.game.tichu.persistence.TichuMatchState;
import com.mirboard.domain.game.tichu.persistence.TichuMatchStateStore;
import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.metrics.MirboardMetrics;
import com.mirboard.testsupport.LogCapture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * D-131 — 티츄 매치 기록이 실패해도 매치는 끝나고, 같은 이벤트의 <b>다른 리스너(칩 정산)</b>도 돈다.
 *
 * <p>D-116 뒤 {@code TichuGameEngine} 은 {@code TichuMatchCompleted} 를 {@code ApplicationEventPublisher} 로 동기 발행하고
 * 리스너가 둘이다 — {@code MatchResultRecorder}(기록, DB) 와 {@code RoomChipService}(칩 정산, Redis). 스프링 멀티캐스터는
 * 리스너를 차례로 부르다 <b>첫 예외에서 멈추고</b> 그 예외를 발행자에게 던진다. 그래서 기록 예외가 (1) 진행 경로로 새어
 * 마지막 방송·리매치 대기(사람끼리 방은 IN_GAME 유지)를 건너뛰고, (2) 기록이 먼저 불리는 순서면 칩 정산까지 빠졌다. 발행
 * 지점에서 잡으면 (1)만 막고 (2)는 남는다 — 기록기가 자기 트랜잭션의 예외(본문·커밋 모두)를 삼킨다.
 *
 * <p>진짜 스프링 이벤트 배선(@EventListener 처리기·멀티캐스터)에 두 리스너를 실제 객체로 올리고, 저장소·브로커만 모의로
 * 둔다. 리스너 순서는 등록 순서를 따르므로 두 순서를 모두 본다. Docker 불필요.
 */
class TichuRecorderFailureTest {

    private static final String ROOM = "r1";
    private static final List<Long> PLAYERS = List.of(10L, 20L, 30L, 40L);
    private static final int STAKE = 100;

    /** 두 리스너의 등록(= 호출) 순서. */
    enum ListenerOrder { RECORDER_FIRST, CHIPS_FIRST }

    private final TichuMatchResultRepository matchRepo = mock(TichuMatchResultRepository.class);
    private final UserGameStatsService stats = mock(UserGameStatsService.class);
    private final BotUserRegistry bots = mock(BotUserRegistry.class);
    private final FakeTransactions transactions = new FakeTransactions();
    private final MatchResultRecorder recorder = new MatchResultRecorder(matchRepo,
            mock(TichuMatchParticipantRepository.class), stats, bots, mock(RatedMatchPolicy.class),
            new ObjectMapper(), Clock.systemUTC(), transactions);

    private final RoomChipStore chipStore = mock(RoomChipStore.class);
    private final StompPublisher stomp = mock(StompPublisher.class);
    private final RoomChipService chips =
            new RoomChipService(chipStore, mock(RoomService.class), bots, stomp, Clock.systemUTC());

    private final TichuMatchStateStore matchStateStore = mock(TichuMatchStateStore.class);
    private final RoomService roomService = mock(RoomService.class);

    TichuRecorderFailureTest() {
        when(matchStateStore.load(ROOM)).thenReturn(Optional.of(TichuMatchState.initial(PLAYERS, 1000)));
        when(chipStore.stacks(ROOM)).thenReturn(Map.of(10L, 1000L, 20L, 1000L, 30L, 1000L, 40L, 1000L));
        when(stats.get(anyLong(), anyString()))
                .thenReturn(new UserGameStatsService.GameStats("TICHU", 1000, 0, 0, 0));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ListenerOrder.class)
    void a_normal_match_end_still_settles_chips_waits_for_a_rematch_and_ends_the_match(ListenerOrder order) {
        when(matchRepo.save(any())).thenThrow(
                new DataAccessResourceFailureException("simulated DB outage in MatchResultRecorder"));
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(order);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            // 팀 A 가 목표 1000 에 닿는 라운드 끝 — 매치 종료.
            assertThatCode(() -> progress().advance(engine(events), room(),
                    new TichuState.RoundEnd(players(), 1000, 0), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound.getLast()).isInstanceOf(TichuEvent.MatchEnded.class);
            // 사람끼리 티츄 방은 리매치를 기다린다(D-82) — FINISHED 로 가지 않는다.
            verify(roomService, never()).markFinished(ROOM);
            assertChipsSettled();
            assertRecordFailureLogged(logs, DataAccessResourceFailureException.class);
            assertListenersRanIn(order);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ListenerOrder.class)
    void a_desertion_still_ends_the_match_and_settles_chips(ListenerOrder order) {
        when(matchRepo.save(any())).thenThrow(
                new DataAccessResourceFailureException("simulated DB outage in MatchResultRecorder"));
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(order);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            GameEngine.DesertOutcome outcome = engine(events).desert(0, 10L, outbound);

            assertThat(outcome).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
            assertThat(outbound).singleElement().isInstanceOf(TichuEvent.MatchEnded.class);
            assertChipsSettled();
            assertRecordFailureLogged(logs, DataAccessResourceFailureException.class);
            assertListenersRanIn(order);
        }
    }

    /**
     * 본문은 끝났는데 커밋이 실패하는 경우(연결 끊김·직렬화 실패). 메서드 본문 안의 try/catch 로는 못 잡는다 — 커밋은
     * {@code @Transactional} 프록시가 본문이 돌아온 뒤에 한다. 그래서 기록기는 트랜잭션 경계 자체를 감싼다.
     */
    @Test
    void a_commit_failure_is_isolated_too() {
        TichuMatchResult saved = mock(TichuMatchResult.class);
        when(saved.getId()).thenReturn(1L);
        when(matchRepo.save(any())).thenReturn(saved);
        transactions.commitFailure = new TransactionSystemException("simulated commit failure");
        List<GameEvent> outbound = new ArrayList<>();

        try (AnnotationConfigApplicationContext events = events(ListenerOrder.RECORDER_FIRST);
             LogCapture logs = LogCapture.of(MatchResultRecorder.class)) {
            assertThatCode(() -> progress().advance(engine(events), room(),
                    new TichuState.RoundEnd(players(), 1000, 0), outbound))
                    .doesNotThrowAnyException();

            assertThat(outbound.getLast()).isInstanceOf(TichuEvent.MatchEnded.class);
            assertChipsSettled();
            assertRecordFailureLogged(logs, TransactionSystemException.class);
        }
    }

    // ---------- helpers ----------

    private AnnotationConfigApplicationContext events(ListenerOrder order) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (order == ListenerOrder.RECORDER_FIRST) {
            context.registerBean("recorder", MatchResultRecorder.class, () -> recorder);
            context.registerBean("chips", RoomChipService.class, () -> chips);
        } else {
            context.registerBean("chips", RoomChipService.class, () -> chips);
            context.registerBean("recorder", MatchResultRecorder.class, () -> recorder);
        }
        context.refresh();
        return context;
    }

    private TichuGameEngine engine(AnnotationConfigApplicationContext events) {
        return new TichuGameEngine(new GameContext(ROOM, PLAYERS, 1000, STAKE, List.of()),
                mock(TichuGameStateStore.class), matchStateStore, mock(TichuRoundStarter.class), events);
    }

    private MatchProgressService progress() {
        GameRegistry games = mock(GameRegistry.class);
        when(games.require(TichuGameDefinition.ID)).thenReturn(new TichuGameDefinition(null, null, null, null));
        return new MatchProgressService(roomService, mock(MirboardMetrics.class), games);
    }

    private static Room room() {
        return new Room(ROOM, "방", TichuGameDefinition.ID, 10L, RoomStatus.IN_GAME, 4, 4, PLAYERS, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, STAKE, Set.of());
    }

    private static List<PlayerState> players() {
        return IntStream.range(0, 4)
                .mapToObj(seat -> PlayerState.initial(seat, List.of(Card.normal(Suit.JADE, 5))))
                .toList();
    }

    private void assertChipsSettled() {
        verify(chipStore).setStacks(eq(ROOM), any());
        verify(stomp).publishToTopic(eq("/topic/room/" + ROOM), any());
    }

    /**
     * 파라미터가 정말 그 순서로 돌았는지 — "등록 순서 = 호출 순서"는 가정이다. 한쪽에 {@code @Order} 가 붙는 식으로 가정이
     * 깨지면 두 파라미터가 같은 순서로 돌아도 나머지 단언은 통과한다(최종 리뷰 S5bT2-M1). 운영 순서는 컴포넌트 스캔상
     * RECORDER_FIRST(domain → infra)다.
     */
    private void assertListenersRanIn(ListenerOrder order) {
        InOrder calls = inOrder(matchRepo, chipStore);
        if (order == ListenerOrder.RECORDER_FIRST) {
            calls.verify(matchRepo).save(any());
            calls.verify(chipStore).setStacks(eq(ROOM), any());
        } else {
            calls.verify(chipStore).setStacks(eq(ROOM), any());
            calls.verify(matchRepo).save(any());
        }
    }

    /**
     * 결과를 실은 ERROR 에 <b>스택</b>도 붙어야 한다 — 본문 예외(DB)인지 커밋 실패인지는 스택으로만 가를 수 있다(Sentry 는
     * ERROR 를 올린다). 문장만 보면 {@code err={}}·{@code e.toString()} 식으로 바뀌어도 통과했다.
     */
    private static void assertRecordFailureLogged(LogCapture logs, Class<? extends Throwable> cause) {
        assertThat(logs.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Tichu match record failed").contains("room=" + ROOM);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(cause.getName());
        });
    }

    /** 커밋만 실패시킬 수 있는 가짜 트랜잭션 관리자 — DB 없이 "본문은 끝났는데 커밋이 실패"를 만든다. */
    private static final class FakeTransactions implements PlatformTransactionManager {

        RuntimeException commitFailure;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
