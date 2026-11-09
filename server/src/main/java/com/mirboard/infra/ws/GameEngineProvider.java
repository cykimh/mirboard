package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import org.springframework.stereotype.Component;

/**
 * D-98 — 인게임 디스패치의 <b>유일한</b> 진입점. 방 → gameType → {@link GameEngine} 로
 * 가는 경로를 여기 하나로 모아 컨트롤러·스케줄러가 게임 이름을 직접 쓰지 않게 한다.
 *
 * <p>D-97 이 지적한 "{@code newEngine()} 호출부 0건"이 해소되는 지점이다. 새 게임을
 * 추가할 때 본 클래스는 손대지 않는다 — {@code GameDefinition} 빈만 등록하면
 * {@link GameRegistry} 가 자동 수집한다 (D-06/D-11 의 약속).
 */
@Component
public class GameEngineProvider {

    private final GameRegistry games;

    public GameEngineProvider(GameRegistry games) {
        this.games = games;
    }

    /**
     * 이 방의 per-room 엔진. 방 설정(목표점수·판돈·봇 좌석·옵션·턴 제한)을 {@link GameContext} 에 실어
     * 엔진이 매치 종료를 스스로 판정할 수 있게 한다. D-183 — 턴 제한은 차례 전체 마감을 엔진 타이머로 거는 게임(요트)이 쓴다.
     *
     * <p>D-176 — 방 옵션 값은 <b>게임이 선언한 것만</b> 싣는다({@link RoomService#effectiveOptions} — 선언한 옵션은 늘 값이 있고
     * 저장돼 있어도 선언하지 않은 옵션은 빠진다). 옵션을 안 쓰는 게임은 빈 맵이다.
     *
     * @throws com.mirboard.domain.game.core.GameNotFoundException 알 수 없는/비활성 게임
     */
    public GameEngine forRoom(Room room) {
        GameDefinition def = games.require(room.gameType());
        return def.newEngine(new GameContext(
                room.roomId(),
                room.playerIds(),
                room.targetScore(),
                room.stake(),
                room.botSeats(),
                RoomService.effectiveOptions(def, room),
                room.turnSeconds()));
    }
}
