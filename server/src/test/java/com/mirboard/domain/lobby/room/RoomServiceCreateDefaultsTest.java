package com.mirboard.domain.lobby.room;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-131 — 방 만들기에서 인원·턴 제한을 <b>생략</b>하면 서버도 게임 선언(D-130 {@code defaultPlayers()}·
 * {@code defaultTurnSeconds()})을 쓴다. 예전에는 서버 생략 기본이 최대 인원·끔이라 모달의 처음 선택(원카드 4명·30초)과
 * 갈렸다 — 지금 클라는 둘 다 보내 실제로 갈리지 않았지만, 다른 클라(스크립트·구버전)가 생략하면 원카드가 6석·턴 제한 없이
 * 열렸다. 티츄·스컬킹의 선언은 예전 생략 기본(최대 인원·끔)과 같아 동작이 바뀌지 않는다. 정의는 저장소를 생성자로 받지만
 * 이 경로는 쓰지 않아 null(원카드는 모의 저장소)로 만든다.
 */
class RoomServiceCreateDefaultsTest {

    private final RoomRepository repository = mock(RoomRepository.class);
    private final GameRegistry games = mock(GameRegistry.class);
    private final RoomService service = new RoomService(repository, games, Clock.systemUTC(),
            mock(ApplicationEventPublisher.class), mock(MirboardMetrics.class), mock(BotUserRegistry.class));

    RoomServiceCreateDefaultsTest() {
        register(new TichuGameDefinition(null, null, null, null));
        register(new SkullKingGameDefinition(null, null, null));
        register(new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                GameStatus.AVAILABLE, 3_000, 1_000, 2_500, 1_000, 2_500));
        when(repository.findById(anyString())).thenReturn(Optional.of(new Room("r", "방", "ANY", 7L,
                RoomStatus.WAITING, 4, 1, List.of(7L), Set.of(), TeamPolicy.SEQUENTIAL, 0L, false, List.of(),
                1000, 0, 0, Set.of())));
    }

    private void register(GameDefinition def) {
        when(games.require(def.id())).thenReturn(def);
    }

    private void createOmittingCapacityAndTurnLimit(String gameType) {
        service.createRoom(7L, "방", gameType, TeamPolicy.SEQUENTIAL, false, RoomService.DEFAULT_TARGET_SCORE,
                null, RoomService.DEFAULT_STAKE, null);
    }

    /** 저장소에 들어간 인원·턴 제한. 나머지 인자는 이 테스트의 관심이 아니다. */
    private void verifyStored(int capacity, int turnSeconds) {
        verify(repository).create(anyString(), anyLong(), anyString(), anyString(), eq(capacity), anyLong(),
                eq(TeamPolicy.SEQUENTIAL), anyBoolean(), anyInt(), eq(turnSeconds), anyInt());
    }

    @Test
    void one_card_omitted_choices_follow_its_declaration_four_seats_and_thirty_seconds() {
        createOmittingCapacityAndTurnLimit(OneCardGameDefinition.ID);

        verifyStored(4, 30);
    }

    @Test
    void tichu_omitted_choices_stay_four_seats_without_a_turn_limit() {
        createOmittingCapacityAndTurnLimit(TichuGameDefinition.ID);

        verifyStored(4, 0);
    }

    @Test
    void skull_king_omitted_choices_stay_eight_seats_without_a_turn_limit() {
        createOmittingCapacityAndTurnLimit(SkullKingGameDefinition.ID);

        verifyStored(8, 0);
    }

    /** 보낸 값이 이긴다 — 원카드를 6명·턴 제한 끔(0)으로 만들 수 있다. */
    @Test
    void explicit_choices_win_over_the_declaration() {
        service.createRoom(7L, "방", OneCardGameDefinition.ID, TeamPolicy.SEQUENTIAL, false,
                RoomService.DEFAULT_TARGET_SCORE, 0, RoomService.DEFAULT_STAKE, 6);

        verifyStored(6, 0);
    }
}
