package com.mirboard.infra.ws;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.metrics.MirboardMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 9C — 액션 적용 후 라운드/매치 진행. GameStompController · BotScheduler ·
 * TurnTimeoutScheduler 세 호출자가 같은 후속 절차를 따르도록 하는 공유 지점.
 *
 * <p>D-98 이후 <b>게임 판단은 여기 없다</b>. "라운드가 끝났나 / 점수를 어떻게 누적하나 /
 * 매치가 끝났나 / 다음 라운드를 어떻게 시작하나"는 전부 {@link GameEngine#advance} 가
 * 답하고, 본 서비스는 그 결과로 <b>인프라 관심사</b>만 처리한다 — 메트릭과 방 상태.
 *
 * <p>호출자는 {@link RoomActionLock} 을 이미 보유하고 있어야 한다 — 본 서비스는 락을
 * 직접 다루지 않는다.
 */
@Service
public class MatchProgressService {

    private static final Logger log = LoggerFactory.getLogger(MatchProgressService.class);

    private final RoomService roomService;
    private final MirboardMetrics metrics;
    private final GameRegistry games;

    public MatchProgressService(RoomService roomService, MirboardMetrics metrics,
                                GameRegistry games) {
        this.roomService = roomService;
        this.metrics = metrics;
        this.games = games;
    }

    /**
     * 액션 적용 직후 호출. 엔진이 라운드 종료를 감지하면 추가 이벤트(매치 종료 / 다음
     * 라운드 시작)를 {@code outbound} 에 append 하므로, 호출자는 이 다음에 한 번만
     * 브로드캐스트하면 된다. 라운드가 안 끝났으면 no-op.
     */
    public void advance(GameEngine engine, Room room, GameState newState, List<GameEvent> outbound) {
        GameEngine.Advance advance = engine.advance(newState, outbound);
        if (!advance.roundCompleted()) {
            return;
        }
        metrics.roundCompleted(room.gameType());
        if (!advance.matchCompleted()) {
            return;
        }
        metrics.matchCompleted(room.gameType());
        if (awaitsRematch(room)) {
            return;
        }
        try {
            roomService.markFinished(room.roomId());
        } catch (RuntimeException e) {
            log.warn("Failed to mark room {} finished: {}", room.roomId(), e.getMessage());
        }
    }

    /**
     * D-82 — 사람만의 매치는 방을 IN_GAME 으로 유지해 호스트가 '한 판 더'(리매치)로 같은
     * 테이블에서 칩 누적 플레이할 수 있게 한다. 봇 포함 매치는 리매치 대상이 아니다.
     *
     * <p>D-122 — 리매치는 <b>게임이 선언</b>한다({@code GameDefinition.supportsRematch()},
     * 기본 false). 리매치가 없는 게임의 사람끼리 방이 IN_GAME 으로 남으면, 끝난 뒤 '나가기'가
     * 탈주 판정(해당 없음) → 일반 leave 로 흘러 좌석 목록을 당겼다.
     */
    private boolean awaitsRematch(Room room) {
        return room.botSeats().isEmpty()
                && games.require(room.gameType()).supportsRematch();
    }
}
