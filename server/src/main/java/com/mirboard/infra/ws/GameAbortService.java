package com.mirboard.infra.ws;

import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.infra.bot.TurnTimeoutScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D-122 — 호스트·어드민 강제 종료(abort)의 인프라 절차. 두 컨트롤러가 공유한다.
 *
 * <p>FINISHED 전이와 턴 데드라인 취소를 <b>방 액션 락 안에서</b> 한다. 액션·봇·타임아웃은 락을
 * 잡은 뒤 방이 IN_GAME 인지 재확인하고 곧바로 적용하는데, abort 가 락 밖에서 전이하면 그 확인과
 * 적용 사이에 끼어 FINISHED 뒤에 액션 1건이 적용·브로드캐스트됐다(스컬킹이면 그 액션이 끝낸
 * 라운드의 정산과 다음 라운드 시작까지). 락 안에서 하면 진행 중 액션이 끝난 뒤에 전이하고, 그
 * 뒤의 액션은 락 안 재확인에서 멈춘다. {@link DesertionService} 의 MATCH_ENDED 와 같은 규칙이다.
 *
 * <p>획득은 탈주 처리와 같은 재시도({@link RoomActionLock#acquireWaiting})다. 끝내 못 잡으면
 * (비정상 경합) 거절하지 않고 <b>락 없이</b> 끝낸다 — 강제 종료는 끊긴 사람이 돌아오지 않을 때의
 * 탈출구라서다. 그 경우에만 액션 1건이 FINISHED 뒤에 적용될 수 있다(이후는 방 상태 가드로 정지).
 * 검증(호스트 여부·진행 중 여부)은 도메인 {@link RoomService} 가 락 안에서 한다. 호스트 요청은 락을
 * 잡기 <b>전에도</b> 한 번 검사한다 — 그러지 않으면 방 밖의 인증 사용자도 POST /abort 를 반복해
 * 라이브 방의 락을 쥐고 실제 플레이어 액션을 BUSY 로 막을 수 있다.
 */
@Service
public class GameAbortService {

    private static final Logger log = LoggerFactory.getLogger(GameAbortService.class);

    private final RoomService rooms;
    private final RoomActionLock lock;
    private final TurnTimeoutScheduler turnTimeout;

    public GameAbortService(RoomService rooms,
                            RoomActionLock lock,
                            TurnTimeoutScheduler turnTimeout) {
        this.rooms = rooms;
        this.lock = lock;
        this.turnTimeout = turnTimeout;
    }

    /** 호스트 강제 종료 (Phase 8A). 호스트 아님·진행 중 아님은 도메인 예외 그대로. */
    public void abortByHost(String roomId, long hostUserId) {
        rooms.checkHostAbort(roomId, hostUserId);
        abort(roomId, () -> rooms.abortGame(roomId, hostUserId));
    }

    /** 어드민 강제 종료 (D-86). 권한 검사는 호출 측(AdminController)이 끝냈다. */
    public void abortByAdmin(String roomId) {
        abort(roomId, () -> rooms.adminAbortGame(roomId));
    }

    private void abort(String roomId, Runnable finishRoom) {
        boolean locked = lock.acquireWaiting(roomId);
        if (!locked) {
            log.warn("Abort: 방 락 획득 실패 — 락 없이 강제 종료한다. roomId={}", roomId);
        }
        try {
            finishRoom.run();
            // 락 안에서 취소해야 락을 기다리던 타임아웃 발화가 넘겨받은 뒤 옛 generation 으로
            // 통과하지 못한다(발화 쪽 방 상태 가드와 이중 방어).
            turnTimeout.cancel(roomId);
        } finally {
            if (locked) {
                lock.release(roomId);
            }
        }
    }
}
