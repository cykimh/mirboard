package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.core.TeamModeTestGame;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-176 E1·E2 — 맵 방 옵션과 게임이 정하는 정원·시작 인원(저장소는 모의 — Docker 불필요). 게임은 {@link TeamModeTestGame}
 * (1:1 = 정원 2, 2:2 = 정원 4, 시작 인원 = 정원). 원자성(확인 뒤 누가 앉는 경합)은 Lua 가 보고 통합 테스트가 확인한다.
 */
class RoomServiceRoomOptionsTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(),
            mock(ApplicationEventPublisher.class), mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    RoomServiceRoomOptionsTest() {
        when(games.require(TeamModeTestGame.ID)).thenReturn(new TeamModeTestGame());
    }

    private static Room room(RoomStatus status, int capacity, Map<RoomOption, String> options, Long... players) {
        List<Long> ids = List.of(players);
        return new Room(ROOM_ID, "방", TeamModeTestGame.ID, HOST, status, capacity, ids.size(), ids, Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 0, 0, Set.of(), Room.DEFAULT_RACE_BUTTON_SIZE,
                Room.DEFAULT_RACE_BUTTON_VISIBILITY, options);
    }

    private static Map<RoomOption, String> solo() {
        return Map.of(RoomOption.TEAM_MODE, "SOLO", RoomOption.BOT_LEVEL, "NORMAL");
    }

    private static Map<RoomOption, String> teams() {
        return Map.of(RoomOption.TEAM_MODE, "TEAMS_2V2", RoomOption.BOT_LEVEL, "NORMAL");
    }

    private void given(Room room) {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    // ── 만들기 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("만들 때 선언한 맵 옵션의 기본값을 같이 쓰고, 정원은 그 옵션의 정원(1:1 → 2)")
    void create_writes_defaults_and_uses_option_capacity() {
        when(repository.findById(anyString())).thenReturn(Optional.of(room(RoomStatus.WAITING, 2, solo(), HOST)));

        service.createRoom(HOST, "방", TeamModeTestGame.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, null, 0, null);

        verify(repository).create(anyString(), eq(HOST), eq("방"), eq(TeamModeTestGame.ID), eq(2), anyLong(),
                eq(TeamPolicy.SEQUENTIAL), eq(false), anyInt(), anyInt(), eq(0),
                eq(Map.of("opt:TEAM_MODE", "SOLO", "opt:BOT_LEVEL", "NORMAL")));
    }

    @Test
    @DisplayName("만들 때 요청 인원이 옵션 정원을 넘으면 INVALID_CAPACITY (1:1 기본인데 4명)")
    void create_rejects_capacity_above_option_capacity() {
        assertThatThrownBy(() -> service.createRoom(HOST, "방", TeamModeTestGame.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, null, 0, 4))
                .isInstanceOf(InvalidCapacityException.class);
    }

    // ── 설정 변경 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("1:1 → 2:2 — 옵션과 새 정원 4를 함께, 앉은 인원 상한 4로 쓴다")
    void switching_to_teams_raises_capacity() {
        given(room(RoomStatus.WAITING, 2, solo(), HOST, 10L));
        when(repository.updateSettings(eq(ROOM_ID), anyMap(), eq(4))).thenReturn(1);

        service.updateSettings(ROOM_ID, HOST, RoomSettingsUpdate.ofOptions(Map.of("TEAM_MODE", "TEAMS_2V2")));

        verify(repository).updateSettings(ROOM_ID, Map.of("opt:TEAM_MODE", "TEAMS_2V2", "capacity", "4"), 4);
    }

    @Test
    @DisplayName("2:2 → 1:1 인데 3명이면 TOO_MANY_PLAYERS — 저장소를 건드리지 않는다")
    void switching_to_solo_with_three_players_is_refused() {
        given(room(RoomStatus.WAITING, 4, teams(), HOST, 10L, 11L));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST,
                RoomSettingsUpdate.ofOptions(Map.of("TEAM_MODE", "SOLO"))))
                .isInstanceOf(TooManyPlayersException.class)
                .extracting(e -> ((TooManyPlayersException) e).capacity()).isEqualTo(2);
        verify(repository, never()).updateSettings(anyString(), anyMap(), anyInt());
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("2:2 → 1:1 확인 뒤 누가 앉았으면(Lua -3) TOO_MANY_PLAYERS")
    void lua_too_many_players() {
        given(room(RoomStatus.WAITING, 4, teams(), HOST, 10L));
        when(repository.updateSettings(eq(ROOM_ID), anyMap(), eq(2))).thenReturn(-3);

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST,
                RoomSettingsUpdate.ofOptions(Map.of("TEAM_MODE", "SOLO"))))
                .isInstanceOf(TooManyPlayersException.class);
    }

    @Test
    @DisplayName("정원이 안 바뀌는 옵션(봇 난이도)은 정원·인원 확인 없이 쓴다")
    void bot_level_does_not_touch_capacity() {
        given(room(RoomStatus.WAITING, 2, solo(), HOST, 10L));
        when(repository.updateSettings(ROOM_ID, Map.of("opt:BOT_LEVEL", "HARD"))).thenReturn(1);

        service.updateSettings(ROOM_ID, HOST, RoomSettingsUpdate.ofOptions(Map.of("BOT_LEVEL", "HARD")));

        verify(repository).updateSettings(ROOM_ID, Map.of("opt:BOT_LEVEL", "HARD"));
    }

    @Test
    @DisplayName("저장된 값이 없는 옛 방도 기본값(1:1)에서 출발한다")
    void missing_stored_options_start_from_defaults() {
        given(room(RoomStatus.WAITING, 2, Map.of(), HOST));
        when(repository.updateSettings(eq(ROOM_ID), anyMap(), eq(4))).thenReturn(1);

        service.updateSettings(ROOM_ID, HOST, RoomSettingsUpdate.ofOptions(Map.of("TEAM_MODE", "TEAMS_2V2")));

        verify(repository).updateSettings(ROOM_ID, Map.of("opt:TEAM_MODE", "TEAMS_2V2", "capacity", "4"), 4);
    }

    @Test
    @DisplayName("선언하지 않은 옵션 — 기본 아닌 값은 UNSUPPORTED_ROOM_OPTION, 기본값은 무시")
    void undeclared_option() {
        given(room(RoomStatus.WAITING, 2, solo(), HOST));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST,
                RoomSettingsUpdate.ofOptions(Map.of("OMOK_RULE", "FREESTYLE"))))
                .isInstanceOf(UnsupportedRoomOptionException.class);

        Room same = service.updateSettings(ROOM_ID, HOST, RoomSettingsUpdate.ofOptions(Map.of("OMOK_RULE", "RENJU")));
        assertThat(same.options()).isEqualTo(solo());
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("허용값 밖·모르는 이름·자기 필드가 있는 옵션은 INVALID_ROOM_SETTING")
    void invalid_values() {
        given(room(RoomStatus.WAITING, 2, solo(), HOST));

        for (Map<String, String> bad : List.of(
                Map.of("TEAM_MODE", "THREE_WAY"),
                Map.of("NOPE", "X"),
                Map.of("TARGET_SCORE", "500"))) {
            assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, RoomSettingsUpdate.ofOptions(bad)))
                    .as(bad.toString())
                    .isInstanceOf(InvalidRoomSettingException.class);
        }
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    // ── 시작·대기실 복귀 ──────────────────────────────────────────────────

    @Test
    @DisplayName("2:2 방장 시작은 시작 인원 4를 넘긴다 — 3명이면 Lua -4 → NOT_ENOUGH_PLAYERS(4)")
    void start_needs_four_in_teams_mode() {
        given(room(RoomStatus.WAITING, 4, teams(), HOST, 10L, 11L));
        when(repository.startNow(ROOM_ID, HOST, 4)).thenReturn(-4);

        assertThatThrownBy(() -> service.startNow(ROOM_ID, HOST))
                .isInstanceOf(NotEnoughPlayersException.class)
                .extracting(e -> ((NotEnoughPlayersException) e).minPlayers()).isEqualTo(4);
    }

    @Test
    @DisplayName("1:1 방장 시작은 시작 인원 2를 넘긴다")
    void start_needs_two_in_solo_mode() {
        given(room(RoomStatus.WAITING, 2, solo(), HOST));
        when(repository.startNow(ROOM_ID, HOST, 2)).thenReturn(-4);

        assertThatThrownBy(() -> service.startNow(ROOM_ID, HOST)).isInstanceOf(NotEnoughPlayersException.class);
        verify(repository).startNow(ROOM_ID, HOST, 2);
    }

    @Test
    @DisplayName("대기실 복귀 정원도 옵션의 정원(2:2 → 4, 1:1 → 2)")
    void return_to_waiting_uses_option_capacity() {
        given(room(RoomStatus.IN_GAME, 4, teams(), HOST, 10L, 11L, 12L));
        when(repository.returnToWaiting(eq(ROOM_ID), anyInt(), anyLong())).thenReturn(4);
        service.returnToWaiting(ROOM_ID);
        verify(repository).returnToWaiting(eq(ROOM_ID), eq(4), anyLong());

        given(room(RoomStatus.IN_GAME, 2, solo(), HOST, 10L));
        service.returnToWaiting(ROOM_ID);
        verify(repository).returnToWaiting(eq(ROOM_ID), eq(2), anyLong());
    }
}
