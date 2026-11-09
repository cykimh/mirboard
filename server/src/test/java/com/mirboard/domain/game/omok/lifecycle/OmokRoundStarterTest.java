package com.mirboard.domain.game.omok.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.game.core.GameStartingEvent;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.invariant.OmokInvariantChecker;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import com.mirboard.domain.game.omok.rules.TurnOrder;
import com.mirboard.domain.game.omok.state.OmokState;
import com.mirboard.infra.bot.BotScheduler;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** 방이 게임을 시작하면 색·첫 수를 추첨해 빈 판을 저장하고 봇 루프·타이머를 건다 (룰 §3). */
class OmokRoundStarterTest {

    private final OmokStateStore store = mock(OmokStateStore.class);
    private final BotScheduler bots = mock(BotScheduler.class);
    private final TurnTimeoutScheduler timers = mock(TurnTimeoutScheduler.class);

    private OmokRoundStarter starter(long seed) {
        return new OmokRoundStarter(store, new Random(seed), bots, timers);
    }

    private static GameStartingEvent event(String gameType, int players, Map<RoomOption, String> options) {
        List<Long> ids = LongStream.range(0, players).map(i -> 100 + i).boxed().toList();
        return new GameStartingEvent("room-1", gameType, ids, 0, options);
    }

    private OmokState started(long seed, int players, Map<RoomOption, String> options) {
        org.mockito.Mockito.clearInvocations(store);
        starter(seed).onGameStarting(event("OMOK", players, options));
        ArgumentCaptor<OmokState> saved = ArgumentCaptor.forClass(OmokState.class);
        verify(store).save(eq("room-1"), saved.capture());
        return saved.getValue();
    }

    @Test
    void a_solo_game_is_saved_and_handed_to_the_bots_and_timers() {
        starter(1).onGameStarting(event("OMOK", 2, Map.of(RoomOption.TEAM_MODE, "SOLO", RoomOption.OMOK_RULE, "RENJU")));
        ArgumentCaptor<OmokState> saved = ArgumentCaptor.forClass(OmokState.class);
        InOrder order = inOrder(store, bots, timers);
        order.verify(store).save(eq("room-1"), saved.capture());
        order.verify(bots).scheduleBots("room-1");
        order.verify(timers).onTurnAdvanced("room-1");
        OmokState s = saved.getValue();
        assertThat(s.mode()).isEqualTo(OmokMode.SOLO);
        assertThat(s.rule()).isEqualTo(OmokRule.RENJU);
        assertThat(s.moves()).isEmpty();
        assertThat(s.turnSeat()).isEqualTo(s.firstSeat()).isEqualTo(s.blackTeam());
        OmokInvariantChecker.check(s);
    }

    @Test
    void the_room_options_choose_the_mode_and_rule() {
        OmokState s = started(2, 4, Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2", RoomOption.OMOK_RULE, "FREESTYLE"));
        assertThat(s.mode()).isEqualTo(OmokMode.TEAMS_2V2);
        assertThat(s.rule()).isEqualTo(OmokRule.FREESTYLE);
        assertThat(TurnOrder.team(s.firstSeat())).isEqualTo(s.blackTeam());
        OmokInvariantChecker.check(s);
    }

    @Test
    void colors_and_the_first_team_member_are_drawn() {
        // 한 스타터(한 난수열)로 여러 판 — java.util.Random 은 이웃한 시드의 첫 값이 서로 닮아 시드를 바꿔 가며 보면 안 된다.
        OmokRoundStarter one = starter(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            org.mockito.Mockito.clearInvocations(store);
            one.onGameStarting(event("OMOK", 4, Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2")));
            ArgumentCaptor<OmokState> saved = ArgumentCaptor.forClass(OmokState.class);
            verify(store).save(eq("room-1"), saved.capture());
            seen.add(saved.getValue().blackTeam() + "/" + saved.getValue().firstSeat());
        }
        assertThat(seen).containsExactlyInAnyOrder("0/0", "0/2", "1/1", "1/3");
    }

    @Test
    void missing_options_default_to_solo_renju() {
        OmokState s = started(3, 2, Map.of());
        assertThat(s.mode()).isEqualTo(OmokMode.SOLO);
        assertThat(s.rule()).isEqualTo(OmokRule.RENJU);
    }

    @Test
    void other_games_and_a_seat_count_that_does_not_match_the_mode_are_ignored() {
        starter(1).onGameStarting(event("ONE_CARD", 2, Map.of()));
        starter(1).onGameStarting(event("OMOK", 3, Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2")));
        starter(1).onGameStarting(event("OMOK", 4, Map.of(RoomOption.TEAM_MODE, "SOLO")));
        verify(store, never()).save(anyString(), any());
        verify(bots, never()).scheduleBots(anyString());
    }
}
