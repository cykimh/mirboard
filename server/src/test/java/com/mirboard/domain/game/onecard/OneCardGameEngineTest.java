package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.onecard.action.OneCardAction.CallOneCard;
import com.mirboard.domain.game.onecard.action.OneCardAction.Catch;
import com.mirboard.domain.game.onecard.action.OneCardAction.PlayCard;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceOutcome;
import com.mirboard.domain.game.onecard.event.OneCardEvent.RaceResolved;
import com.mirboard.domain.game.onecard.event.OneCardMatchCompleted;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.PrivateView;
import com.mirboard.domain.game.onecard.state.OneCardStateMapper.TableView;
import com.mirboard.testsupport.LogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * D-128 — 포트 어댑터. 시계를 넣는 자리(경쟁 창 여는 시각·엔진 타이머의 남은 시간), 매치 종료 기록 발행,
 * 탈주 3치 매핑. 저장소는 모의 객체라 Docker 없이 돈다.
 */
class OneCardGameEngineTest {

    private static final long NOW = 50_000L;
    /** 봇 반응 고정 — 주인 1.2초, 잡는 쪽 1.5초. */
    private static final RaceSettings FIXED = new RaceSettings(3_000, 1_200, 1_200, 1_500, 1_500, 8);

    private final OneCardStateStore store = mock(OneCardStateStore.class);
    private final List<Object> published = new ArrayList<>();

    private OneCardGameEngine engine(long now, int seatCount, Integer... botSeats) {
        return engine(published::add, now, seatCount, botSeats);
    }

    private OneCardGameEngine engine(ApplicationEventPublisher publisher, long now, int seatCount,
                                     Integer... botSeats) {
        List<Long> ids = LongStream.range(0, seatCount).map(i -> 100 + i).boxed().toList();
        return new OneCardGameEngine(new GameContext("room-1", ids, 0, 0, List.of(botSeats)), store,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), new Random(5), FIXED, publisher);
    }

    /** 좌석 0 이 두 장 중 ♥9 를 내 1장이 되는 3인 테이블. */
    private static OneCardState aboutToGoDownToOne() {
        return seats(hand(heart(9), club(3)), hand(spade(4), spade(6), spade(8)),
                hand(diamond(4), diamond(6), diamond(8)))
                .top(heart(5)).turn(0).build();
    }

    private static OneCardState raced(OneCardGameEngine engine) {
        return (OneCardState) engine.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9))).newState();
    }

    @Test
    void a_race_opens_at_the_clock_time() {
        GameEngine.Result result = engine(NOW, 3).apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        OneCardState next = (OneCardState) result.newState();
        assertThat(next.race().openedAt()).isEqualTo(NOW);
        assertThat(result.events()).last().isInstanceOf(OneCardEvent.RaceOpened.class);
    }

    @Test
    void the_timer_is_the_time_left_until_the_window_ends_or_the_fastest_bot_presses() {
        OneCardState noBots = raced(engine(NOW, 3));
        OneCardState withCatcherBot = raced(engine(NOW, 3, 2));

        assertThat(engine(NOW + 1_000, 3).timer(noBots)).contains(Duration.ofMillis(2_000));
        assertThat(engine(NOW + 9_000, 3).timer(noBots)).contains(Duration.ZERO);
        assertThat(engine(NOW + 1_000, 3, 2).timer(withCatcherBot)).contains(Duration.ofMillis(500));
        assertThat(engine(NOW, 3).timer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_timer_closes_the_race() {
        OneCardState raced = raced(engine(NOW, 3));

        Optional<GameEngine.Result> fired = engine(NOW + 3_000, 3).onTimer(raced);

        assertThat(fired).isPresent();
        assertThat(fired.get().events().getFirst())
                .isEqualTo(new RaceResolved(raced.race().raceId(), RaceOutcome.EXPIRED, -1));
        assertThat(engine(NOW, 3).onTimer(aboutToGoDownToOne())).isEmpty();
    }

    @Test
    void the_transition_that_ends_the_match_is_recorded_exactly_once() {
        OneCardState lastCard = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                .top(heart(5)).turn(0).build();
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(lastCard, 0, PlayCard.of(heart(9)));

        GameEngine.Advance advance = adapter.advance(result.newState(), new ArrayList<>(result.events()));

        assertThat(advance).isEqualTo(new GameEngine.Advance(true, true));
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class, done -> {
            assertThat(done.roomId()).isEqualTo("room-1");
            assertThat(done.playerIds()).containsExactly(100L, 101L, 102L);
            assertThat(done.result().reason()).isEqualTo(EndReason.FINISHED);
            assertThat(done.result().winners()).containsExactly(0);
        });
        assertThat(adapter.advance(result.newState(), new ArrayList<>())).isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).hasSize(1);
    }

    @Test
    void nothing_advances_while_the_match_goes_on() {
        OneCardGameEngine adapter = engine(NOW, 3);
        GameEngine.Result result = adapter.apply(aboutToGoDownToOne(), 0, PlayCard.of(heart(9)));

        assertThat(adapter.advance(result.newState(), new ArrayList<>(result.events())))
                .isEqualTo(GameEngine.Advance.NONE);
        assertThat(published).isEmpty();
    }

    @Test
    void desertion_maps_to_the_three_port_outcomes() {
        OneCardState threeSeats = aboutToGoDownToOne();
        OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
        OneCardState alreadyOut = seats(hand(heart(9), club(3)), hand(), hand(diamond(4), diamond(6)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20).top(heart(5)).turn(0).build();
        List<GameEvent> outbound = new ArrayList<>();

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);

        when(store.load("room-1")).thenReturn(Optional.of(alreadyOut));
        assertThat(engine(NOW, 3).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.NOT_APPLICABLE);
        verify(store, never()).save(anyString(), any());
        assertThat(outbound).isEmpty();

        when(store.load("room-1")).thenReturn(Optional.of(threeSeats));
        assertThat(engine(NOW, 3).desert(2, 102L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_CONTINUES);
        verify(store).save(eq("room-1"), argThat(saved -> !saved.alive(2) && !saved.ended()));
        assertThat(outbound).isNotEmpty();
        assertThat(published).isEmpty();

        outbound.clear();
        when(store.load("room-1")).thenReturn(Optional.of(twoSeats));
        assertThat(engine(NOW, 2).desert(1, 101L, outbound)).isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);
        assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
        assertThat(published).singleElement().isInstanceOfSatisfying(OneCardMatchCompleted.class,
                done -> assertThat(done.result().reason()).isEqualTo(EndReason.LAST_STANDING));
    }

    @Test
    void views_and_queries_read_the_state_with_the_clock() {
        OneCardState raced = raced(engine(NOW, 3));
        OneCardGameEngine later = engine(NOW + 1_000, 3);

        assertThat(later.phaseName(raced)).isEqualTo("RACE");
        assertThat(later.pendingSeats(raced)).isEmpty();
        assertThat(later.isRoundOver(raced)).isFalse();
        assertThat(((TableView) later.publicView(raced)).race().remainingMillis()).isEqualTo(2_000);
        assertThat(later.privateView(raced, 0)).contains(new PrivateView(0, List.of(club(3)), raced.version()));
        assertThat(later.legalActions(raced, 0)).hasSize(1);

        when(store.load("room-1")).thenReturn(Optional.empty());
        assertThat(later.isMatchOver()).isFalse();
    }

    @Test
    void foreign_state_or_action_types_are_rejected() {
        OneCardGameEngine adapter = engine(NOW, 3);

        assertThatThrownBy(() -> adapter.phaseName(mock(GameState.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.apply(aboutToGoDownToOne(), 0, mock(GameAction.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * S5 — 경쟁 창이 닫힐 때마다 결과 한 줄(INFO). 설계서 §4.4·§7 과 D-128 이 "배포 후 경쟁 결과 로그로 다시 본다"고
     * 미뤄 둔 판단(사람·봇 승률, 반응 시간 분포, 핑 유리, 폴러 지연)과 누름 자동화 탐지의 근거다. 창이 닫히는 세 경로 —
     * 누름(apply)·엔진 타이머(onTimer)·탈주(desert) — 모두에서 남긴다. 사용자별 값이라 로그로만 둔다(메트릭 태그는
     * 공개 {@code /actuator/prometheus} 로 나간다). 열 이름이 바뀌면 로그를 읽는 쪽이 깨지므로 문장 전체를 고정한다.
     */
    @Nested
    class RaceResultLog {

        private List<String> raceLines(LogCapture logs) {
            return logs.messages(Level.INFO).stream().filter(m -> m.startsWith("OneCard race resolved")).toList();
        }

        @Test
        void a_human_catching_a_bot_owner_is_one_line_with_the_reaction_time() {
            OneCardState raced = raced(engine(NOW, 3, 0)); // 주인(좌석 0)이 봇
            int raceId = raced.race().raceId();

            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                engine(NOW + 420, 3, 0).apply(raced, 2, new Catch(raceId));

                assertThat(raceLines(logs)).containsExactly("OneCard race resolved: room=room-1 raceId=" + raceId
                        + " outcome=CAUGHT via=PRESS ownerSeat=0 ownerUser=100 ownerBot=true"
                        + " bySeat=2 byUser=102 byBot=false latencyMs=420 windowMs=3000 lateMs=-");
            }
        }

        @Test
        void the_owner_calling_is_logged_and_opening_a_race_is_not() {
            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                OneCardState raced = raced(engine(NOW, 3));
                assertThat(raceLines(logs)).as("창을 여는 전이는 남기지 않는다").isEmpty();

                engine(NOW + 250, 3).apply(raced, 0, new CallOneCard(raced.race().raceId()));

                assertThat(raceLines(logs)).singleElement().asString()
                        .contains("outcome=CALLED via=PRESS ownerSeat=0 ownerUser=100 ownerBot=false")
                        .contains("bySeat=0 byUser=100 byBot=false latencyMs=250");
            }
        }

        /** 타이머 경로는 마감보다 얼마나 늦게 처리됐는지(lateMs — 단일 폴러 지연의 실측)도 남긴다. */
        @Test
        void a_timer_resolution_also_says_how_late_it_fired() {
            OneCardState botCatcher = raced(engine(NOW, 3, 2)); // 좌석 2 봇이 1.5초에 잡는다
            OneCardState noBots = raced(engine(NOW, 3));

            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                engine(NOW + 1_530, 3, 2).onTimer(botCatcher);
                engine(NOW + 3_080, 3).onTimer(noBots);

                assertThat(raceLines(logs)).containsExactly(
                        "OneCard race resolved: room=room-1 raceId=" + botCatcher.race().raceId()
                                + " outcome=CAUGHT via=TIMER ownerSeat=0 ownerUser=100 ownerBot=false"
                                + " bySeat=2 byUser=102 byBot=true latencyMs=1530 windowMs=3000 lateMs=30",
                        "OneCard race resolved: room=room-1 raceId=" + noBots.race().raceId()
                                + " outcome=EXPIRED via=TIMER ownerSeat=0 ownerUser=100 ownerBot=false"
                                + " bySeat=-1 byUser=- byBot=false latencyMs=3080 windowMs=3000 lateMs=80");
            }
        }

        @Test
        void a_desertion_during_the_race_logs_the_cancellation() {
            OneCardState raced = raced(engine(NOW, 3));
            when(store.load("room-1")).thenReturn(Optional.of(raced));

            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                engine(NOW + 800, 3).desert(1, 101L, new ArrayList<>());

                assertThat(raceLines(logs)).singleElement().asString()
                        .contains("outcome=CANCELLED via=DESERTION")
                        .contains("bySeat=-1 byUser=- byBot=false latencyMs=800 windowMs=3000 lateMs=-");
            }
        }

        /** 탈주가 매치를 끝내는 갈래(MATCH_ENDED)도 같은 줄을 남긴다 — 위 테스트는 매치가 이어지는 갈래만 본다. */
        @Test
        void a_desertion_that_ends_the_match_during_the_race_also_logs() {
            OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
            OneCardState raced = (OneCardState) engine(NOW, 2).apply(twoSeats, 0, PlayCard.of(heart(9))).newState();
            when(store.load("room-1")).thenReturn(Optional.of(raced));

            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                assertThat(engine(NOW + 800, 2).desert(1, 101L, new ArrayList<>()))
                        .isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);

                assertThat(raceLines(logs)).singleElement().asString()
                        .contains("outcome=CANCELLED via=DESERTION")
                        .contains("latencyMs=800");
            }
        }
    }

    /**
     * S5 — 매치 종료 기록기({@code OneCardMatchRecorder})는 동기 리스너라 DB 장애가 어댑터로 올라온다. 예전에는 그대로
     * 던져 호출한 진행 경로가 저장 뒤 방송·FINISHED 전이·재무장을 건너뛰었다 — 마지막 {@code CARD_PLAYED}·
     * {@code MATCH_ENDED} 가 아무에게도 안 가고 방은 IN_GAME 에 남았다. 기록이 빠지는 쪽이 덜 아프다: 결과를 실어
     * ERROR 로 남기고(수동 복구용) 진행은 계속한다.
     */
    @Nested
    class RecorderFailure {

        private final ApplicationEventPublisher failingRecorder = event -> {
            throw new DataAccessResourceFailureException("simulated DB outage in OneCardMatchRecorder");
        };

        @Test
        void the_last_card_still_ends_the_match_and_keeps_the_events_to_broadcast() {
            OneCardState lastCard = seats(hand(heart(9)), hand(spade(4), spade(6)), hand(diamond(4), diamond(6)))
                    .top(heart(5)).turn(0).build();
            OneCardGameEngine adapter = engine(failingRecorder, NOW, 3);
            GameEngine.Result result = adapter.apply(lastCard, 0, PlayCard.of(heart(9)));
            List<GameEvent> outbound = new ArrayList<>(result.events());

            try (LogCapture logs = LogCapture.of(OneCardGameEngine.class)) {
                assertThat(adapter.advance(result.newState(), outbound)).isEqualTo(new GameEngine.Advance(true, true));

                assertThat(logs.events()).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("room=room-1").contains("FINISHED");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
            }
            assertThat(outbound).first().isInstanceOf(OneCardEvent.CardPlayed.class);
            assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
        }

        @Test
        void a_desertion_that_ends_the_match_still_reports_match_ended() {
            OneCardState twoSeats = seats(hand(heart(9), club(3)), hand(spade(4), spade(6))).top(heart(5)).turn(0).build();
            when(store.load("room-1")).thenReturn(Optional.of(twoSeats));
            List<GameEvent> outbound = new ArrayList<>();

            assertThat(engine(failingRecorder, NOW, 2).desert(1, 101L, outbound))
                    .isEqualTo(GameEngine.DesertOutcome.MATCH_ENDED);

            verify(store).save(eq("room-1"), argThat(OneCardState::ended));
            assertThat(outbound).last().isInstanceOf(OneCardEvent.MatchEnded.class);
        }
    }
}
