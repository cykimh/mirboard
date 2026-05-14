package com.mirboard.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Mirboard 도메인 카운터 묶음. Phase 6A-4 에서 도입.
 *
 * <p>Prometheus 노출 시 metric 이름은 {@code mirboard_<area>_<event>_total} 패턴.
 * MeterRegistry 가 인스턴스화 시점에 모든 카운터를 미리 등록해두면, 첫 호출에서
 * 0 -> 1 로 가는 동안의 race 가 없고 스크래퍼가 항상 동일한 시계열을 본다.
 */
@Component
public class MirboardMetrics {

    private final Counter roomCreated;
    private final Counter roomJoined;
    private final Counter gameStarted;
    private final Counter roundCompleted;
    private final Counter matchCompleted;
    private final Counter actionRejected;

    public MirboardMetrics(MeterRegistry registry) {
        this.roomCreated = Counter.builder("mirboard.room.created")
                .description("방 생성 누적")
                .register(registry);
        this.roomJoined = Counter.builder("mirboard.room.joined")
                .description("방 입장 성공 누적")
                .register(registry);
        this.gameStarted = Counter.builder("mirboard.game.started")
                .description("게임 시작 (방이 capacity 도달) 누적")
                .tag("gameType", "TICHU")
                .register(registry);
        this.roundCompleted = Counter.builder("mirboard.round.completed")
                .description("티츄 라운드 완료 누적")
                .register(registry);
        this.matchCompleted = Counter.builder("mirboard.match.completed")
                .description("티츄 매치 (1000점) 종료 누적")
                .register(registry);
        this.actionRejected = Counter.builder("mirboard.action.rejected")
                .description("STOMP 액션 검증 실패 누적")
                .register(registry);
    }

    public void roomCreated() { roomCreated.increment(); }
    public void roomJoined() { roomJoined.increment(); }
    public void gameStarted() { gameStarted.increment(); }
    public void roundCompleted() { roundCompleted.increment(); }
    public void matchCompleted() { matchCompleted.increment(); }
    public void actionRejected() { actionRejected.increment(); }
}
