package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU_CLUES;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.T0;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEngine.DesertOutcome;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.starsquare.action.RejectionReason;
import com.mirboard.domain.game.starsquare.action.StarSquareActionRejectedException;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.event.StarSquareMatchCompleted;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper.PrivateBoard;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper.PublicView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** S2 포트 어댑터 — 방 설정 → 엔진 설정, 엔진 타이머(재무장해도 처음부터 세지 않음), 뷰 은닉, 낡은 SYNC, 탈주·기록 발행. Docker 불필요. */
class StarSquareGameEngineTest {

    private static final List<Long> PLAYERS = List.of(10L, 11L, 12L);

    private final StarSquareStateStore store = mock(StarSquareStateStore.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private StarSquareGameEngine adapter(long now, ApplicationEventPublisher pub) {
        GameContext ctx = new GameContext("r1", PLAYERS, 0, 0, List.of(2),
                Map.of(RoomOption.MATCH_LENGTH, "SHORT", RoomOption.DIFFICULTY, "EASY", RoomOption.BOT_LEVEL, "NORMAL"),
                300);
        return new StarSquareGameEngine(ctx, StarSquareConfig.from(ctx, 1.0), store,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), pub);
    }

    /** 준비가 끝나 첫 퍼즐이 열린 상태(시작 T0 + 3초). */
    private StarSquareState playing() {
        StarSquareEngine e = adapter(T0, publisher).rules();
        StarSquareState intro = e.start(loShuPuzzles(1), 9L, T0).newState();
        return e.onTimer(intro, T0 + StarSquareEngine.INTRO_MS).newState();
    }

    @Test
    void room_options_become_the_engine_config() {
        GameContext ctx = new GameContext("r", List.of(1L, 2L), 0, 0, List.of(1),
                Map.of(RoomOption.MATCH_LENGTH, "LONG", RoomOption.DIFFICULTY, "HARD", RoomOption.BOT_LEVEL, "EASY"), 0);
        StarSquareConfig c = StarSquareConfig.from(ctx, 0.5);
        assertThat(c.puzzleCount()).isEqualTo(5);
        assertThat(c.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(c.botLevel()).isEqualTo(BotLevel.EASY);
        assertThat(c.limitSeconds()).isEqualTo(1_200); // 자동 → 어려움 20분
        assertThat(c.botSeats()).containsExactly(1);
        assertThat(c.timeScale()).isEqualTo(0.5);
        // 옵션이 비면 기본(보통 3개, 보통 난이도·보통 봇)
        StarSquareConfig d = StarSquareConfig.from(new GameContext("r", List.of(1L)), 1.0);
        assertThat(d.puzzleCount()).isEqualTo(3);
        assertThat(d.difficulty()).isEqualTo(Difficulty.NORMAL);
        assertThat(d.limitSeconds()).isEqualTo(420);
    }

    @Test
    void the_engine_timer_counts_from_the_stored_start_so_rearming_never_restarts_it() {
        StarSquareState s = playing();
        long start = s.phaseStartedAt();
        // 액션이 와서 인프라가 재무장해도 남은 시간은 퍼즐 시작 기준 — 시계만큼 줄어든다
        StarSquareGameEngine at10 = adapter(start + 10_000, publisher);
        StarSquareState after = (StarSquareState) at10.apply(s, 0, sync(0, 1, 2, 7, 6, 0, 0, 1, 0, 0, 0)).newState();
        Duration first = at10.timer(after).orElseThrow();
        Duration later = adapter(start + 70_000, publisher).timer(after).orElseThrow();
        // 봇 이정표가 더 이르면 그것이 다음 전이다 — 어느 쪽이든 시작 기준이라 60초 사이에 정확히 60초 줄거나 0 에 닿는다
        assertThat(first.minus(later).toMillis()).isIn(60_000L, first.toMillis());
        assertThat(adapter(start + 999_999, publisher).timer(after)).contains(Duration.ZERO);
    }

    @Test
    void on_timer_opens_the_first_puzzle_after_the_intro_and_is_empty_before() {
        StarSquareGameEngine early = adapter(T0 + 1_000, publisher);
        StarSquareState intro = early.rules().start(loShuPuzzles(1), 9L, T0).newState();
        assertThat(early.onTimer(intro)).isEmpty();
        assertThat(early.pendingSeats(intro)).isEmpty();
        GameEngine.Result r = adapter(T0 + 3_000, publisher).onTimer(intro).orElseThrow();
        assertThat(r.events()).first().isInstanceOf(StarSquareEvent.PuzzleStarted.class);
        assertThat(adapter(T0, publisher).pendingSeats(r.newState())).containsExactly(0, 1); // 봇 좌석 2 는 대기 좌석이 아니다
        assertThat(adapter(T0, publisher).phaseName(r.newState())).isEqualTo("PLAYING");
    }

    @Test
    void views_hide_the_solution_and_other_boards_and_the_private_view_is_my_board_only() throws Exception {
        StarSquareState s = playing();
        StarSquareGameEngine a = adapter(s.phaseStartedAt() + 1_000, publisher);
        s = (StarSquareState) a.apply(s, 0, sync(0, 3, 2, 7, 6, 0, 0, 1, 0, 0, 0)).newState();
        ObjectMapper mapper = new ObjectMapper();
        String publicJson = mapper.writeValueAsString(a.publicView(s));
        assertThat(publicJson).doesNotContain("solution", "\"grid\"", "matchSeed", "pace")
                .doesNotContain("[2,7,6,9,5,1,4,3,8]").doesNotContain("[2,7,6,0,0,1,0,0,0]");
        assertThat(((PublicView) a.publicView(s)).remainingMs()).isEqualTo(299_000);

        PrivateBoard mine = (PrivateBoard) a.privateView(s, 0).orElseThrow();
        assertThat(mine.grid().toList()).containsExactly(2, 7, 6, 0, 0, 1, 0, 0, 0);
        assertThat(mine.version()).isEqualTo(3);
        assertThat(((PrivateBoard) a.privateView(s, 1).orElseThrow()).grid()).isEqualTo(LO_SHU_CLUES);
        assertThat(a.privateView(s, 2)).as("봇 좌석").isEmpty();
        // 이벤트 JSON 에도 정답이 없다(진행도 수만)
        GameEngine.Result r = a.apply(s, 1, sync(0, 1, 2, 7, 6, 0, 5, 1, 0, 0, 0));
        for (GameEvent ev : r.events()) {
            assertThat(mapper.writeValueAsString(ev)).doesNotContain("solution", "grid");
        }
    }

    @Test
    void a_stale_sync_is_refused_quietly_and_changes_nothing() {
        StarSquareState s = playing();
        StarSquareGameEngine a = adapter(s.phaseStartedAt() + 1_000, publisher);
        StarSquareState v5 = (StarSquareState) a.apply(s, 0, sync(0, 5, 2, 7, 6, 0, 0, 1, 0, 0, 0)).newState();
        assertThatThrownBy(() -> a.apply(v5, 0, sync(0, 4, LO_SHU.toArray())))
                .isInstanceOf(StarSquareActionRejectedException.class)
                .satisfies(ex -> {
                    StarSquareActionRejectedException rejected = (StarSquareActionRejectedException) ex;
                    assertThat(rejected.code()).isEqualTo("STALE_SYNC");
                    assertThat(rejected.reason().quiet()).isTrue();
                });
        // 폴백(타임아웃) = 저장된 판·버전 그대로 → 무변화
        assertThat(a.apply(v5, 0, a.timeoutAction(v5, 0)).newState()).isEqualTo(v5);
        assertThat(a.botAction(v5, 2, new Random(1))).as("봇은 대기 좌석이 아니다").isNull();
        assertThat(RejectionReason.valueOf("STALE_SYNC")).isNotNull();
    }

    @Test
    void the_match_end_publishes_the_record_once_and_a_failing_recorder_does_not_stop_it() {
        StarSquareState s = playing();
        long t = s.phaseStartedAt() + 2_000;
        StarSquareGameEngine a = adapter(t, publisher);
        s = (StarSquareState) a.apply(s, 0, sync(0, 1, LO_SHU.toArray())).newState();
        s = (StarSquareState) a.apply(s, 1, sync(0, 1, LO_SHU.toArray())).newState();
        // 봇(좌석 2)은 아직 — 첫 완성 + 15초 마감이 퍼즐(짧게 = 1개)과 매치를 끝낸다
        StarSquareGameEngine closing = adapter(t + StarSquareEngine.CLOSE_AFTER_FIRST_MS, publisher);
        GameEngine.Result last = closing.onTimer(s).orElseThrow();
        assertThat(last.events()).last().isInstanceOf(StarSquareEvent.MatchEnded.class);
        List<GameEvent> outbound = new ArrayList<>(last.events());
        assertThat(closing.advance(last.newState(), outbound)).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOf(StarSquareMatchCompleted.class);
        StarSquareMatchCompleted done = (StarSquareMatchCompleted) published.get(0);
        assertThat(done.playerIds()).containsExactly(10L, 11L, 12L);
        assertThat(done.result().reason()).isEqualTo(MatchResult.EndReason.FINISHED);
        // 끝나지 않은 전이는 기록하지 않는다
        assertThat(a.advance(s, new ArrayList<>())).isEqualTo(GameEngine.Advance.NONE);

        ApplicationEventPublisher broken = event -> {
            throw new IllegalStateException("db down");
        };
        assertThat(adapter(t, broken).advance(last.newState(), new ArrayList<>(last.events())))
                .isEqualTo(new GameEngine.Advance(true, true));
    }

    @Test
    void desertion_maps_to_the_port_outcome_and_saves_the_state() {
        StarSquareState s = playing();
        when(store.load("r1")).thenReturn(Optional.of(s));
        StarSquareGameEngine a = adapter(s.phaseStartedAt() + 1_000, publisher);
        List<GameEvent> out = new ArrayList<>();
        assertThat(a.desert(1, 11L, out)).isEqualTo(DesertOutcome.MATCH_CONTINUES);
        assertThat(out).singleElement().isEqualTo(new StarSquareEvent.SeatDeserted(1));
        verify(store).save(eq("r1"), any(StarSquareState.class));
        assertThat(published).isEmpty();

        // 남은 사람 0(좌석 0 사람·2 봇) → 매치 끝 + 기록 발행
        StarSquareState oneLeft = a.rules().desert(s, 1, s.phaseStartedAt()).newState();
        when(store.load("r1")).thenReturn(Optional.of(oneLeft));
        List<GameEvent> out2 = new ArrayList<>();
        assertThat(a.desert(0, 10L, out2)).isEqualTo(DesertOutcome.MATCH_ENDED);
        assertThat(published).singleElement().isInstanceOf(StarSquareMatchCompleted.class);
        assertThat(((StarSquareMatchCompleted) published.get(0)).result().reason())
                .isEqualTo(MatchResult.EndReason.NO_HUMANS);

        when(store.load("r1")).thenReturn(Optional.empty());
        assertThat(a.desert(0, 10L, new ArrayList<>())).isEqualTo(DesertOutcome.NOT_APPLICABLE);
        verify(store, never()).save(eq("other"), any());
    }
}
