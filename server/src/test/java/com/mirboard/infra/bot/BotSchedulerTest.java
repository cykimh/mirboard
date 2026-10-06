package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.testsupport.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * S5 — 봇 루프는 이 인스턴스에서 방마다 몇 개가 살아 있는지 센다. 진행 킥({@link GameProgressKick})은 resync·구독마다
 * 불리므로, 살아 있는 루프가 있는데도 하나 더 걸면 두 루프가 번갈아 락을 잡아 봇이 지연 없이 연달아 둔다. 그래서 킥은
 * {@link BotScheduler#scheduleBotsIfIdle} 로만 건다. 루프가 "봇이 기다리지 않음"으로 끝나는 것은 그런 겹친 루프·사람
 * 차례 인계마다 일어나는 정상 경로라 DEBUG 로 남긴다(예전 WARN 은 봇 방 사람 차례마다 Sentry breadcrumb 을 채웠다).
 */
class BotSchedulerTest {

    private static final String ROOM = "r1";

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final RoomActionLock lock = mock(RoomActionLock.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    private BotScheduler scheduler(long botDelayMillis) {
        return new BotScheduler(roomService, engines, mock(GameEventBroadcaster.class), lock,
                mock(MatchProgressService.class), mock(BotUserRegistry.class), mock(TurnTimeoutScheduler.class),
                1L, botDelayMillis);
    }

    /** 봇(좌석 1)이 있는 진행 중 방인데 지금은 사람(좌석 0) 차례 — 루프는 지연 뒤 락을 잡고 할 일 없이 끝난다. */
    private void humanTurnInABotRoom() {
        when(roomService.getRoom(ROOM)).thenReturn(new Room(ROOM, "방", "ANY", 10L, RoomStatus.IN_GAME, 2, 2,
                List.of(10L, 20L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, true, List.of(1), 1000, 0, 0, Set.of()));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.pendingSeats(state)).thenReturn(List.of(0));
        when(engine.phaseName(state)).thenReturn("PLAYING");
    }

    @Test
    void the_idle_entry_starts_no_second_loop_while_one_is_alive() {
        humanTurnInABotRoom();
        when(lock.tryAcquire(ROOM)).thenReturn(true);
        BotScheduler bots = scheduler(300);

        bots.scheduleBots(ROOM); // 300ms 지연 중인 살아 있는 루프

        assertThat(bots.scheduleBotsIfIdle(ROOM)).as("살아 있는 루프 위에 겹쳐 걸지 않는다").isFalse();
        verify(lock, timeout(2_000)).release(ROOM);
        // 첫 루프가 끝나면 다시 걸 수 있고, 그렇게 건 루프도 살아 있는 루프로 센다.
        await().atMost(Duration.ofSeconds(2)).until(() -> bots.scheduleBotsIfIdle(ROOM));
        assertThat(bots.scheduleBotsIfIdle(ROOM)).isFalse();
    }

    /**
     * 락 경합으로 잠시 뒤 다시 도는 토막도 같은 루프다 — 그 사이에 킥이 하나 더 걸면 안 된다. 재시도 토막이 락에 들어온
     * 시점(첫 토막은 50ms + 지연 전에 이미 끝났다)에 붙잡고 단언해야 재시도 토막을 세는지 가려진다 — 첫 토막이 살아 있는
     * 동안 단언하면 계수를 지워도 통과한다(사전 리뷰 M-1).
     */
    @Test
    void only_the_retry_segment_is_alive_and_still_blocks_a_second_loop() throws Exception {
        humanTurnInABotRoom();
        CountDownLatch retrying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        when(lock.tryAcquire(ROOM)).thenAnswer(i -> {
            if (attempts.incrementAndGet() == 1) {
                return false; // 첫 토막 → 재시도 토막을 걸고 끝난다
            }
            retrying.countDown(); // 재시도 토막이 50ms + 지연 뒤 여기 왔다 — 첫 토막은 끝난 지 오래다
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        BotScheduler bots = scheduler(20);

        bots.scheduleBots(ROOM);
        assertThat(retrying.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(bots.scheduleBotsIfIdle(ROOM)).as("재시도 토막만 살아 있어도 겹쳐 걸지 않는다").isFalse();
        } finally {
            release.countDown();
        }
        verify(lock, timeout(2_000)).release(ROOM);
    }

    @Test
    void a_loop_ending_on_a_human_turn_is_a_debug_line_not_a_warning() {
        humanTurnInABotRoom();
        when(lock.tryAcquire(ROOM)).thenReturn(true);

        try (LogCapture logs = LogCapture.of(BotScheduler.class)) {
            scheduler(0).scheduleBots(ROOM);
            verify(lock, timeout(2_000)).release(ROOM);

            await().atMost(Duration.ofSeconds(2)).until(() -> logs.events().stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("no pending bot action")));
            assertThat(logs.events())
                    .filteredOn(e -> e.getFormattedMessage().contains("no pending bot action"))
                    .extracting(ILoggingEvent::getLevel)
                    .containsOnly(Level.DEBUG);
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }
    }
}
