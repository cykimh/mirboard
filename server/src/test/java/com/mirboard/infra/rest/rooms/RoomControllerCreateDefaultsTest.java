package com.mirboard.infra.rest.rooms;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mirboard.domain.lobby.auth.AuthPrincipal;
import com.mirboard.domain.lobby.room.RoomChipStore;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.bot.GameProgressKick;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import com.mirboard.infra.ws.DesertionService;
import com.mirboard.infra.ws.GameAbortService;
import com.mirboard.infra.ws.GameEngineProvider;
import com.mirboard.infra.ws.RoomActionLock;
import com.mirboard.infra.ws.RoomPresence;
import com.mirboard.infra.ws.RoomSeq;
import org.junit.jupiter.api.Test;

/**
 * D-131 — 방 만들기 요청에서 생략한 인원·턴 제한은 컨트롤러가 채우지 않고 그대로(null) 넘긴다. 생략 기본은 게임 선언이고
 * 그 판단은 {@code RoomService} 한 곳이다 — 예전에는 컨트롤러가 턴 제한 생략을 0(끔)으로 바꿔 넘겨, 서비스가 게임 선언을 쓸
 * 길이 없었다.
 */
class RoomControllerCreateDefaultsTest {

    private final RoomService rooms = mock(RoomService.class);
    private final RoomController controller = new RoomController(rooms, mock(GameEngineProvider.class),
            mock(RoomSeq.class), mock(DesertionService.class), mock(RoomPresence.class), mock(RoomChipStore.class),
            mock(GameAbortService.class), mock(RoomActionLock.class), mock(GameProgressKick.class),
            mock(TurnTimeoutScheduler.class));

    @Test
    void omitted_capacity_and_turn_limit_reach_the_service_as_omitted() {
        controller.create(new AuthPrincipal(10L, "me"),
                new RoomController.CreateRequest("방", "ONE_CARD", null, null, null, null, null, null));

        verify(rooms).createRoom(eq(10L), eq("방"), eq("ONE_CARD"), eq(TeamPolicy.SEQUENTIAL), eq(false),
                eq(RoomService.DEFAULT_TARGET_SCORE), isNull(), eq(RoomService.DEFAULT_STAKE), isNull());
    }

    @Test
    void sent_choices_pass_through_unchanged() {
        controller.create(new AuthPrincipal(10L, "me"),
                new RoomController.CreateRequest("방", "ONE_CARD", null, null, null, 60, null, 5));

        verify(rooms).createRoom(eq(10L), eq("방"), eq("ONE_CARD"), eq(TeamPolicy.SEQUENTIAL), eq(false),
                eq(RoomService.DEFAULT_TARGET_SCORE), eq(60), eq(RoomService.DEFAULT_STAKE), eq(5));
    }
}
