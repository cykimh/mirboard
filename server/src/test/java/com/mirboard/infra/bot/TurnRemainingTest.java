package com.mirboard.infra.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.MatchProgressService;
import com.mirboard.infra.ws.RoomActionLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * D-131 — 턴의 남은 시간. resync 응답이 실어 클라 카운트다운이 재접속 직후에도 맞게 한다. 값은 서버가 실제로 발화할
 * 데드라인 그대로다 — 지금 세대의 {@code deadlines:turn} 항목 점수 − 지금(음수면 0). 게임 중립이라 엔진은 모의 객체로
 * 충분하다.
 */
class TurnRemainingTest {

    private static final String ROOM = "r1";

    private static Room room(RoomStatus status, int turnSeconds) {
        return new Room(ROOM, "방", "ANY", 10L, status, 2, 2, List.of(10L, 20L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, turnSeconds, 0, Set.of());
    }

    /** 실제 {@link DeadlineQueue} 를 가짜 시계·모의 Redis 위에 올린다 — 점수 − 지금의 계산을 그대로 본다. */
    @Nested
    class Remaining {

        private static final long NOW = 1_000_000L;

        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        private final DeadlineQueue deadlines = new DeadlineQueue(redis, mock(RedisScript.class),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        private final RoomGeneration generations = mock(RoomGeneration.class);
        private final TurnTimeoutScheduler scheduler = new TurnTimeoutScheduler(mock(RoomService.class),
                mock(GameEngineProvider.class), mock(GameEventBroadcaster.class), mock(RoomActionLock.class),
                mock(MatchProgressService.class), mock(BotScheduler.class), deadlines, generations);

        Remaining() {
            when(redis.opsForZSet()).thenReturn(zset);
            when(generations.current(ROOM)).thenReturn(5L);
        }

        @Test
        void the_armed_turn_answers_the_time_left_until_its_deadline() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW + 12_345));

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30)))
                    .contains(Duration.ofMillis(12_345));
        }

        @Test
        void a_room_without_a_turn_limit_answers_nothing() {
            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 0))).isEmpty();

            verifyNoInteractions(zset, generations);
        }

        /** 폴러가 아직 꺼내지 않은 만기 항목(폴링 주기·락 경합 재시도) — 음수가 아니라 0 이다. */
        @Test
        void an_overdue_deadline_answers_zero() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW - 500));

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30))).contains(Duration.ZERO);
        }

        /**
         * 세대가 오른 뒤 남은 이전 세대 항목은 발화해도 세대 검사에서 버려진다 — 남은 시간도 아니다. 지금 세대의 항목만 본다
         * (그 사이 이 세대의 항목이 아직 없으면 "걸린 데드라인 없음").
         */
        @Test
        void an_entry_of_an_older_generation_is_ignored() {
            when(zset.score("deadlines:turn", "r1#4")).thenReturn((double) (NOW + 9_000));
            when(zset.score("deadlines:turn", "r1#5")).thenReturn(null);   // 모의 객체의 기본값(0.0)이 아니라 "없음".

            assertThat(scheduler.turnRemaining(room(RoomStatus.IN_GAME, 30))).isEmpty();
        }

        @Test
        void a_room_that_is_not_in_game_answers_nothing() {
            when(zset.score("deadlines:turn", "r1#5")).thenReturn((double) (NOW + 12_345));

            assertThat(scheduler.turnRemaining(room(RoomStatus.FINISHED, 30))).isEmpty();
            assertThat(scheduler.turnRemaining(room(RoomStatus.WAITING, 30))).isEmpty();
        }
    }

    /**
     * D-131 — 시간 초과 자동 액션은 다음 턴 데드라인을 <b>락을 풀기 전에</b> 건다. resync 는 같은 락 안에서 상태와 남은 시간을
     * 함께 읽으므로, 락을 푼 뒤에 걸면 그 틈에 들어온 resync 가 새 상태와 <b>이전 턴의</b> 남은 시간(또는 없음)을 받았다. 봇
     * 루프는 락을 풀고 건다(호출자가 락을 쥔 채 루프를 걸면 루프가 이 락과 부딪쳐 재시도한다).
     */
    @Nested
    class RearmInsideTheLock {

        private final RoomService roomService = mock(RoomService.class);
        private final GameEngineProvider engines = mock(GameEngineProvider.class);
        private final GameEventBroadcaster broadcaster = mock(GameEventBroadcaster.class);
        private final RoomActionLock lock = mock(RoomActionLock.class);
        private final BotScheduler botScheduler = mock(BotScheduler.class);
        private final DeadlineQueue deadlines = mock(DeadlineQueue.class);
        private final RoomGeneration generations = mock(RoomGeneration.class);
        private final TurnTimeoutScheduler scheduler = new TurnTimeoutScheduler(roomService, engines, broadcaster,
                lock, mock(MatchProgressService.class), botScheduler, deadlines, generations);

        @Test
        void a_timeout_auto_action_rearms_the_next_turn_before_releasing_the_lock() {
            GameEngine engine = mock(GameEngine.class);
            GameState state = mock(GameState.class);
            GameAction action = mock(GameAction.class);
            when(roomService.getRoom(ROOM)).thenReturn(room(RoomStatus.IN_GAME, 30));
            when(generations.current(ROOM)).thenReturn(5L);
            when(generations.bump(ROOM)).thenReturn(6L);
            when(lock.tryAcquire(ROOM)).thenReturn(true);
            when(engines.forRoom(any())).thenReturn(engine);
            when(engine.loadState()).thenReturn(Optional.of(state));
            when(engine.pendingSeat(state)).thenReturn(0);
            when(engine.timeoutAction(state, 0)).thenReturn(action);
            when(engine.apply(state, 0, action)).thenReturn(new GameEngine.Result(state, List.of()));

            scheduler.handle("r1#5");

            InOrder order = inOrder(broadcaster, generations, deadlines, lock, botScheduler);
            order.verify(broadcaster).broadcast(eq(ROOM), any(), any());
            order.verify(generations).bump(ROOM);
            order.verify(deadlines).schedule(TurnTimeoutScheduler.KIND, "r1#6", Duration.ofSeconds(30));
            order.verify(lock).release(ROOM);
            order.verify(botScheduler).scheduleBots(ROOM);
        }
    }
}
