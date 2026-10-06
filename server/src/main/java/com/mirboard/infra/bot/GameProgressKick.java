package com.mirboard.infra.bot;

import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomNotFoundException;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.RoomStatus;
import com.mirboard.infra.scheduling.DeadlineQueue;
import com.mirboard.infra.scheduling.RoomGeneration;
import com.mirboard.infra.ws.GameEngineProvider;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * D-130 — 진행 킥. 방 진행이 메모리의 봇 루프와 유실될 수 있는 타이머에만 기대지 않게, 클라가 방을 다시 볼 때 — resync
 * 응답 뒤({@code RoomController}), 게임 토픽 구독({@code WsSessionLifecycleListener}) — 멈춘 진행을 다시 건다.
 *
 * <ul>
 *   <li><b>(a) 봇 루프</b> — 대기 좌석에 봇이 있는데 이 인스턴스에 그 방의 루프가 없으면 건다. 재기동(배포·Fly 자동
 *       정지)은 메모리의 루프를 지우고, 턴 제한을 끈 방(기본)에는 깨워 줄 데드라인도 없어 봇 차례에서 매치가 영구
 *       정지했다(C-I1). 살아 있는 루프 위에는 겹쳐 걸지 않는다({@link BotScheduler#scheduleBotsIfIdle}).</li>
 *   <li><b>(b) 엔진 타이머</b> — 상태가 타이머를 선언했는데 이 세대의 데드라인이 큐에 없으면 현 세대로 다시 건다.
 *       무장 실패·발화 중 실패·저장~무장 사이 종료로 타이머가 사라지면 경쟁 창이 영원히 열려 있었다(C-I2). 그 member 가
 *       <b>없을 때만 더한다</b>({@link DeadlineQueue#scheduleIfAbsent}, ZADD NX) — 확인과 쓰기가 원자라 그 사이 걸린 정상
 *       무장(재시도·미만기 재무장 포함)을 락 없이 읽은 낡은 남은 시간으로 덮지 않는다. 남은 시간은 상태가 정하므로 창을
 *       늘리지도 앞당기지도 않는다(이미 지났으면 0 — 바로 발화). 낡은 킥이 틈에서 걸어도 발화 쪽의 세대·락·{@code timer}
 *       재확인이 그대로 지킨다.</li>
 * </ul>
 *
 * <p><b>참가자·관전자의 킥만 받는다.</b> 공개 토픽 구독은 로그인한 누구나 할 수 있고 SUBSCRIBE 는 레이트리밋 밖이라,
 * 아무나의 구독 폭주가 킥마다 Redis 왕복 몇 번씩으로 커지지 않게 한다(킥은 어차피 방을 읽는다).
 *
 * <p><b>턴 진행({@code onTurnAdvanced})은 부르지 않는다.</b> 부르면 세대가 오르고 턴 데드라인이 처음부터 다시 걸려,
 * resync 를 반복하는 클라가 시간 초과를 끝없이 미룰 수 있다. 끝난 방·대기실·없는 방·시작 전 게임은 아무것도
 * 하지 않는다.
 *
 * <p>게임을 모른다 — 누가 기다리는지·타이머가 있는지는 엔진 포트가 답한다. 호출한 요청(resync 응답·구독 처리)을
 * 늦추지 않게 가상 스레드에서 돌고, 실패는 남기고 삼킨다(킥이 없던 때와 같아질 뿐이다 — 실행기가 제출을 거절한 경우도
 * 같다). {@code Error} 는 ERROR 로 남긴다 — 가상 스레드의 기본 처리기(stderr)로 가면 Sentry 에 보이지 않는다.
 */
@Component
public class GameProgressKick {

    private static final Logger log = LoggerFactory.getLogger(GameProgressKick.class);

    private final RoomService rooms;
    private final GameEngineProvider engines;
    private final BotScheduler bots;
    private final DeadlineQueue deadlines;
    private final RoomGeneration generations;
    private final Executor executor;

    @Autowired
    public GameProgressKick(RoomService rooms,
                            GameEngineProvider engines,
                            BotScheduler bots,
                            DeadlineQueue deadlines,
                            RoomGeneration generations) {
        this(rooms, engines, bots, deadlines, generations,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mirboard-kick-", 0).factory()));
    }

    /** 테스트용 — 실행기를 바꿔 끼운다(직접 실행기면 호출한 스레드에서 바로 돈다). */
    GameProgressKick(RoomService rooms,
                     GameEngineProvider engines,
                     BotScheduler bots,
                     DeadlineQueue deadlines,
                     RoomGeneration generations,
                     Executor executor) {
        this.rooms = rooms;
        this.engines = engines;
        this.bots = bots;
        this.deadlines = deadlines;
        this.generations = generations;
        this.executor = executor;
    }

    /**
     * 비동기 진입점 — 호출한 요청을 늦추지 않는다. 락을 쥔 채 부르지 말 것(봇 루프가 같은 락을 기다린다).
     *
     * @param userId 방을 다시 보는 사람(resync 요청자·게임 토픽 구독자). 참가자·관전자가 아니면 아무것도 안 한다
     */
    public void kick(String roomId, long userId) {
        try {
            executor.execute(() -> {
                try {
                    kickNow(roomId, userId);
                } catch (RuntimeException e) {
                    // 스택까지 남긴다 — 엔진 timer 의 NPE 같은 예기치 못한 실패는 메시지만으로는 어디서 났는지 모른다.
                    log.warn("Progress kick failed: roomId={} err={}", roomId, e.toString(), e);
                } catch (Error e) {
                    log.error("Progress kick failed: roomId={}", roomId, e);
                }
            });
        } catch (RuntimeException e) {
            // D-130 — 제출 자체가 거절돼도(종료 중·자원 부족) 호출한 요청(resync 응답·구독 처리)으로 던지지 않는다.
            log.warn("Progress kick not submitted: roomId={} err={}", roomId, e.toString(), e);
        } catch (Error e) {
            log.error("Progress kick not submitted: roomId={}", roomId, e);
        }
    }

    /** 한 번의 킥. 테스트가 실행기 없이 부를 수 있게 package-private. */
    void kickNow(String roomId, long userId) {
        Room room;
        try {
            room = rooms.getRoom(roomId);
        } catch (RoomNotFoundException e) {
            return;
        }
        if (room.status() != RoomStatus.IN_GAME) {
            return;
        }
        if (!room.playerIds().contains(userId) && !room.spectatorIds().contains(userId)) {
            return;
        }
        GameEngine engine = engines.forRoom(room);
        GameState state = engine.loadState().orElse(null);
        if (state == null || engine.isRoundOver(state)) {
            return;
        }

        boolean botPending = engine.pendingSeats(state).stream().anyMatch(room.botSeats()::contains);
        if (botPending && bots.scheduleBotsIfIdle(roomId)) {
            log.info("Progress kick resumed the bot loop: roomId={} phase={}", roomId, engine.phaseName(state));
        }

        engine.timer(state).ifPresent(left -> rearmIfLost(roomId, left));
    }

    /**
     * (b) — 이 세대의 엔진 타이머가 큐에 없을 때만 남은 시간(이미 지났으면 0)으로 건다. 더한 경우는 유실만이 아니다 — 폴러가
     * pop 한 뒤 그 발화가 세대를 올리기 전(수십 ms)에도 항목이 없다. 그때 더한 항목은 세대 불일치로 버려져 무해하므로 INFO 다.
     */
    private void rearmIfLost(String roomId, Duration left) {
        long gen = generations.current(roomId);
        String member = TurnTimeoutScheduler.member(roomId, gen);
        if (deadlines.scheduleIfAbsent(EngineTimerScheduler.KIND, member, left)) {
            log.info("Progress kick armed an absent engine timer (lost, or popped and still in flight): "
                    + "roomId={} gen={} leftMs={}", roomId, gen, left.toMillis());
        }
    }
}
