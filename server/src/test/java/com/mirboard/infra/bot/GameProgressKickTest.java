package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.testsupport.LogCapture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * S5 — 진행 킥. 방 진행은 메모리의 봇 루프와 유실될 수 있는 타이머에 기대 왔다 — 재기동(배포·Fly 자동 정지) 뒤에는
 * 봇 차례에서 매치가 영구 정지했고(C-I1), 엔진 타이머가 한 번 사라지면 경쟁 창이 영원히 열려 있었다(C-I2). 클라가 방을
 * 다시 볼 때(resync 응답 뒤·게임 토픽 구독) 거는 킥이 (a) 대기 중인 봇의 루프와 (b) 사라진 엔진 타이머를 되살린다.
 * 게임 중립이라 엔진은 모의 객체로 충분하다.
 */
class GameProgressKickTest {

    private static final String ROOM = "r1";
    /** 방의 참가자(좌석 0). 킥은 참가자·관전자의 것만 받는다. */
    private static final long PLAYER = 10L;

    private final RoomService roomService = mock(RoomService.class);
    private final GameEngineProvider engines = mock(GameEngineProvider.class);
    private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
    private final RoomGeneration generations = mock(RoomGeneration.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final GameEngine engine = mock(GameEngine.class);
    private final GameState state = mock(GameState.class);

    /** 직접 실행기 — 테스트에서는 킥을 호출한 스레드에서 바로 돈다. */
    private final GameProgressKick kick =
            new GameProgressKick(roomService, engines, bots, deadlines, generations, Runnable::run);

    private static Room room(RoomStatus status, List<Integer> botSeats, int turnSeconds) {
        return room(status, botSeats, turnSeconds, Set.of());
    }

    private static Room room(RoomStatus status, List<Integer> botSeats, int turnSeconds, Set<Long> spectators) {
        return new Room(ROOM, "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), spectators,
                TeamPolicy.SEQUENTIAL, 0L, !botSeats.isEmpty(), botSeats, 1000, turnSeconds, 0, Set.of());
    }

    private void inGame(List<Integer> botSeats, int turnSeconds, List<Integer> pending) {
        when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, botSeats, turnSeconds));
        when(engines.forRoom(any())).thenReturn(engine);
        when(engine.loadState()).thenReturn(Optional.of(state));
        when(engine.pendingSeats(state)).thenReturn(pending);
    }

    @Nested
    class Bots {

        @Test
        void a_pending_bot_seat_gets_a_loop_only_through_the_idle_check() {
            inGame(List.of(1), 0, List.of(1));

            kick.kickNow(ROOM, PLAYER);

            // 살아 있는 루프가 있으면 겹쳐 걸지 않는 진입점만 쓴다 — 겹치면 봇 속도가 빨라진다.
            verify(bots).scheduleBotsIfIdle(ROOM);
            verify(bots, never()).scheduleBots(anyString());
        }

        @Test
        void a_human_turn_starts_no_bot_loop() {
            inGame(List.of(1), 0, List.of(0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(bots);
        }

        /**
         * 재기동 = 메모리(봇 루프 가상 스레드)는 사라지고 Redis(상태·세대)만 남는다. 턴 제한이 꺼져 있어 데드라인도 없다 —
         * 예전에는 사람이 '나가기'(탈주 기록)를 누를 때까지 영원히 멈췄다. 새 인스턴스의 실제 봇 스케줄러로 확인한다.
         */
        @Test
        void after_a_restart_one_kick_resumes_the_stuck_bot_turn() {
            RoomActionLock lock = mock(RoomActionLock.class);
            GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
            TurnTimeoutScheduler turnTimeout = mock(TurnTimeoutScheduler.class);
            BotScheduler freshInstance = new BotScheduler(roomService, engines, broadcaster, lock,
                    mock(MatchProgressService.class), mock(BotUserRegistry.class), turnTimeout, 1L, 0L);
            GameProgressKick kickOnFreshInstance =
                    new GameProgressKick(roomService, engines, freshInstance, deadlines, generations, Runnable::run);

            GameState afterBot = mock(GameState.class);
            GameAction action = mock(GameAction.class);
            GameEvent played = mock(GameEvent.class);
            AtomicReference<GameState> stored = new AtomicReference<>(state);
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenAnswer(i -> Optional.of(stored.get()));
            doAnswer(i -> {
                stored.set(i.getArgument(0));
                return null;
            }).when(engine).saveState(any());
            when(engine.pendingSeats(state)).thenReturn(List.of(1));
            when(engine.pendingSeats(afterBot)).thenReturn(List.of(0));
            when(engine.botAction(eq(state), eq(1), any())).thenReturn(action);
            when(engine.apply(state, 1, action)).thenReturn(new GameEngine.Result(afterBot, List.of(played)));
            when(lock.tryAcquire(ROOM)).thenReturn(true);

            kickOnFreshInstance.kickNow(ROOM, PLAYER);

            verify(broadcaster, timeout(2_000)).broadcast(eq(ROOM), eq(List.of(played)), eq(List.of(10L, 20L)));
            assertThat(stored.get()).isSameAs(afterBot);
        }
    }

    @Nested
    class EngineTimer {

        @Test
        void a_lost_engine_timer_is_armed_only_if_absent_with_the_current_generation_and_the_time_left() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1_200)));
            when(generations.current(ROOM)).thenReturn(7L);
            when(deadlines.scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ofMillis(1_200)))
                    .thenReturn(true);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                kick.kickNow(ROOM, PLAYER);

                // 그 member 가 없을 때만 더한다(ZADD NX) — 남은 시간은 상태가 정하므로 창을 늘리지도 앞당기지도 않는다.
                verify(deadlines).scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ofMillis(1_200));
                verify(deadlines, never()).schedule(anyString(), anyString(), any());
                // 유실만이 아니라 pop 뒤 처리 중일 때도 더해지므로(무해) 경보가 아니라 INFO 다.
                assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("armed an absent engine timer"));
                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
        }

        /** 10분 뒤 '잡기!' 가 벌칙을 주던 경로 — 이미 끝났어야 할 창은 지금 바로 닫히게 건다. */
        @Test
        void a_lost_timer_already_past_its_deadline_is_armed_to_fire_now() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ZERO));
            when(generations.current(ROOM)).thenReturn(7L);

            kick.kickNow(ROOM, PLAYER);

            verify(deadlines).scheduleIfAbsent(EngineTimerScheduler.KIND, "r1#7", Duration.ZERO);
        }

        /** 이미 걸린 무장(정상·재시도·미만기 재무장)은 덮지 않는다 — 덮는 쪽(`schedule`)을 부르지 않는다. */
        @Test
        void a_timer_still_armed_for_this_generation_is_never_overwritten() {
            inGame(List.of(), 0, List.of());
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ofMillis(1_200)));
            when(generations.current(ROOM)).thenReturn(7L);
            when(deadlines.scheduleIfAbsent(anyString(), anyString(), any())).thenReturn(false);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                kick.kickNow(ROOM, PLAYER);

                verify(deadlines, never()).schedule(anyString(), anyString(), any());
                assertThat(logs.messages(Level.INFO)).noneMatch(m -> m.contains("armed an absent engine timer"));
            }
        }

        @Test
        void a_state_without_a_timer_arms_nothing() {
            inGame(List.of(), 0, List.of(0));
            when(engine.timer(state)).thenReturn(Optional.empty());

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(deadlines);
        }
    }

    @Nested
    class Guards {

        /**
         * resync 를 반복하는 클라가 시간 초과를 끝없이 미루지 못하게 — 킥은 턴 진행({@code onTurnAdvanced})이 아니다.
         * 세대를 올리지도, 턴 데드라인을 다시 걸지도, 걸린 데드라인을 지우지도 않는다.
         */
        @Test
        void kicking_again_and_again_never_extends_the_turn_deadline() {
            inGame(List.of(), 30, List.of(0));
            when(engine.timer(state)).thenReturn(Optional.empty());

            for (int i = 0; i < 5; i++) {
                kick.kickNow(ROOM, PLAYER);
            }

            verify(generations, never()).bump(anyString());
            verify(deadlines, never()).schedule(eq(TurnTimeoutScheduler.KIND), anyString(), any());
            verify(deadlines, never()).scheduleIfAbsent(eq(TurnTimeoutScheduler.KIND), anyString(), any());
            verify(deadlines, never()).cancel(anyString(), anyString());
        }

        /**
         * 공개 토픽 구독은 로그인한 누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이다 — 참가자·관전자가 아니면 아무것도
         * 하지 않는다(구독 폭주가 킥마다 Redis 왕복으로 커지지 않게). 관전자는 판을 보는 사람이라 킥한다.
         */
        @Test
        void a_non_member_subscription_starts_nothing() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0, Set.of(77L)));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.of(state));
            when(engine.pendingSeats(state)).thenReturn(List.of(1));
            when(engine.timer(state)).thenReturn(Optional.of(Duration.ZERO));

            kick.kickNow(ROOM, 999L);
            verifyNoInteractions(engines, bots, deadlines, generations);

            kick.kickNow(ROOM, 77L); // 관전자
            verify(bots).scheduleBotsIfIdle(ROOM);
        }

        @Test
        void a_finished_room_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.FINISHED, List.of(1), 0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(engines, bots, deadlines, generations);
        }

        @Test
        void a_waiting_room_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.WAITING, List.of(1), 0));

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(engines, bots, deadlines, generations);
        }

        @Test
        void a_vanished_room_or_an_unstarted_game_is_left_alone() {
            when(roomService.getRoom(ROOM)).thenThrow(new RoomNotFoundException(ROOM));
            kick.kickNow(ROOM, PLAYER);

            when(roomService.getRoom("r2")).thenReturn(room(RoomStatus.IN_GAME, List.of(1), 0));
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.empty());
            kick.kickNow("r2", PLAYER);

            verifyNoInteractions(bots, deadlines, generations);
        }

        @Test
        void a_round_already_over_is_left_alone() {
            inGame(List.of(1), 0, List.of(1));
            when(engine.isRoundOver(state)).thenReturn(true);

            kick.kickNow(ROOM, PLAYER);

            verifyNoInteractions(bots, deadlines, generations);
        }

        /**
         * resync 응답·구독 처리를 늦추지 않게 실행기로 넘기고, 실패해도 호출한 쪽으로 던지지 않는다. {@code Error} 는 ERROR 로
         * 남긴다 — 가상 스레드의 기본 처리기(stderr)로 가면 Sentry 에 보이지 않는다.
         */
        @Test
        void kick_runs_on_the_executor_and_swallows_failures() {
            List<Runnable> submitted = new ArrayList<>();
            GameProgressKick deferred =
                    new GameProgressKick(roomService, engines, bots, deadlines, generations, submitted::add);
            when(roomService.getRoom(ROOM))
                    .thenThrow(new IllegalStateException("redis down"))
                    .thenThrow(new StackOverflowError("simulated"));

            deferred.kick(ROOM, PLAYER);
            deferred.kick(ROOM, PLAYER);
            verifyNoInteractions(roomService);
            assertThat(submitted).hasSize(2);

            try (LogCapture logs = LogCapture.of(GameProgressKick.class)) {
                assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
                assertThatCode(() -> submitted.get(1).run()).doesNotThrowAnyException();

                assertThat(logs.messages(Level.WARN)).singleElement().asString().contains("redis down");
                assertThat(logs.messages(Level.ERROR)).singleElement().asString().contains("Progress kick failed");
            }
        }
    }
}
