package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-161 — 대기실에서 방장이 게임 설정을 바꾼다(저장소는 모의 — Docker 불필요). 상태 확인과 쓰기를 한 번에 하는 것은
 * {@code room_update_settings.lua} 이고(통합 테스트가 본다), 서비스는 방장 확인·만들 때와 같은 검증·결과 코드 해석·메타 갱신을 맡는다.
 */
class RoomServiceSettingsTest {

    private static final String ROOM_ID = "r1";
    private static final long HOST = 1L;

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(), events,
            mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    private static Room room(String gameType, RoomStatus status) {
        return new Room(ROOM_ID, "방", gameType, HOST, status, 4, 2, List.of(HOST, 10L), Set.of(),
                TeamPolicy.SEQUENTIAL, 0L, false, List.of(), 1000, 30, 0, Set.of());
    }

    @BeforeEach
    void games() {
        GameDefinition oneCard = mock(GameDefinition.class);
        when(oneCard.id()).thenReturn("ONE_CARD");
        when(oneCard.supportedRoomOptions()).thenReturn(Set.of(RoomOption.RACE_BUTTON_SIZE));
        when(games.require("ONE_CARD")).thenReturn(oneCard);
        GameDefinition tichu = mock(GameDefinition.class);
        when(tichu.id()).thenReturn("TICHU");
        when(tichu.supportedRoomOptions())
                .thenReturn(Set.of(RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING));
        when(games.require("TICHU")).thenReturn(tichu);
    }

    @Test
    @DisplayName("방장이 바꾼 항목만 저장하고 메타 갱신을 낸다")
    void host_updates_given_fields() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.WAITING)));
        when(repository.updateSettings(ROOM_ID, Map.of("turnSeconds", "10", "raceButtonSize", "LARGE"))).thenReturn(1);

        service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(10, null, null, "LARGE"));

        verify(repository).updateSettings(ROOM_ID, Map.of("turnSeconds", "10", "raceButtonSize", "LARGE"));
        verify(events).publishEvent(org.mockito.ArgumentMatchers.any(RoomChangedEvent.class));
    }

    @Test
    @DisplayName("티츄는 목표 점수·판돈을 바꾼다")
    void tichu_target_and_stake() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("TICHU", RoomStatus.WAITING)));
        when(repository.updateSettings(ROOM_ID, Map.of("targetScore", "500", "stake", "100"))).thenReturn(1);

        service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, 500, 100, null));

        verify(repository).updateSettings(ROOM_ID, Map.of("targetScore", "500", "stake", "100"));
    }

    @Test
    @DisplayName("방장이 아니면 NOT_HOST — 저장소를 건드리지 않는다")
    void non_host_is_refused() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.WAITING)));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, 10L, new RoomSettingsUpdate(10, null, null, null)))
                .isInstanceOf(NotHostException.class);
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("시작된 방이면 GAME_ALREADY_STARTED")
    void started_room_is_refused() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.IN_GAME)));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(10, null, null, null)))
                .isInstanceOf(GameAlreadyStartedException.class);
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("확인과 쓰기 사이에 시작됐으면(-2) GAME_ALREADY_STARTED")
    void started_between_check_and_write() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.WAITING)));
        when(repository.updateSettings(anyString(), anyMap())).thenReturn(-2);

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(10, null, null, null)))
                .isInstanceOf(GameAlreadyStartedException.class);
    }

    @Test
    @DisplayName("게임이 안 쓰는 설정(원카드 판돈)은 UNSUPPORTED_ROOM_OPTION")
    void unsupported_option() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.WAITING)));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, null, 100, null)))
                .isInstanceOf(UnsupportedRoomOptionException.class);
        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, 500, null, null)))
                .isInstanceOf(UnsupportedRoomOptionException.class);
    }

    @Test
    @DisplayName("티츄 방의 원카드! 버튼 크기는 UNSUPPORTED_ROOM_OPTION")
    void race_size_only_for_one_card() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("TICHU", RoomStatus.WAITING)));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, null, null, "LARGE")))
                .isInstanceOf(UnsupportedRoomOptionException.class);
    }

    @Test
    @DisplayName("값이 틀리면 거절한다 — 버튼 크기·턴 제한·판돈")
    void invalid_values() {
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("ONE_CARD", RoomStatus.WAITING)));

        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, null, null, "HUGE")))
                .isInstanceOf(InvalidRoomSettingException.class);
        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(-1, null, null, null)))
                .isInstanceOf(InvalidRoomSettingException.class);

        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room("TICHU", RoomStatus.WAITING)));
        assertThatThrownBy(() -> service.updateSettings(ROOM_ID, HOST, new RoomSettingsUpdate(null, null, 7, null)))
                .isInstanceOf(InvalidStakeException.class);
        verify(repository, never()).updateSettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("방에 읽힌 버튼 크기 기본은 MEDIUM")
    void race_size_defaults_to_medium() {
        assertThat(room("ONE_CARD", RoomStatus.WAITING).raceButtonSize()).isEqualTo("MEDIUM");
    }
}
