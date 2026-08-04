package com.mirboard.infra.metrics;

import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mirboard 도메인 카운터 묶음. Phase 6A-4 에서 도입, D-107(C3)에서 게임 차원 추가.
 *
 * <p>Prometheus 노출 시 metric 이름은 {@code mirboard_<area>_<event>_total} 패턴.
 * MeterRegistry 가 인스턴스화 시점에 모든 카운터를 미리 등록해두면, 첫 호출에서
 * 0 -> 1 로 가는 동안의 race 가 없고 스크래퍼가 항상 동일한 시계열을 본다.
 *
 * <p><b>게임 차원 (D-107).</b> 게임 활동 카운터는 {@code gameType} 태그로 갈린다. 전에는
 * {@code gameStarted} 에 {@code tag("gameType", "TICHU")} 가 <b>상수로</b> 박혀 있었다 —
 * 티츄 하나일 때는 맞았지만 스컬킹이 붙은 뒤로는 스컬킹 게임이 TICHU 로 집계돼 게임별
 * 대시보드가 조용히 거짓말을 했다. 태그가 없는 것보다 <b>틀린 태그가 더 나쁘다</b>:
 * 없으면 못 나누는 걸 알지만, 틀리면 나눈 결과를 믿는다.
 *
 * <p>미리 등록 성질은 유지한다 — {@link GameRegistry} 에 등록된 게임 전체 × 카운터 조합을
 * 기동 시 만들어 두므로 아직 한 판도 안 돈 게임도 0 값 시계열로 보인다. 로비/프로토콜 레벨
 * 카운터({@code roomJoined}·{@code actionRejected})는 게임 분석축이 아니라 평면으로 둔다 —
 * 태그는 시계열 수를 늘리는 비용이 있어 쓸 곳이 있을 때만 단다.
 */
@Component
public class MirboardMetrics {

    private final MeterRegistry registry;

    /** gameType → 카운터. 기동 시 등록된 게임 전체로 채운다. */
    private final Map<String, Counter> roomOpenedByGame = new HashMap<>();
    private final Map<String, Counter> gameStartedByGame = new HashMap<>();
    private final Map<String, Counter> roundCompletedByGame = new HashMap<>();
    private final Map<String, Counter> matchCompletedByGame = new HashMap<>();

    private final Counter roomJoined;
    private final Counter actionRejected;

    public MirboardMetrics(MeterRegistry registry, GameRegistry games) {
        this.registry = registry;
        for (GameDefinition def : games.catalog()) {
            String id = def.id();
            roomOpenedByGame.put(id, counter(ROOM_OPENED, "방 생성 누적", id));
            gameStartedByGame.put(id, counter(GAME_STARTED, GAME_STARTED_DESC, id));
            roundCompletedByGame.put(id, counter(ROUND_COMPLETED, "라운드 완료 누적", id));
            matchCompletedByGame.put(id, counter(MATCH_COMPLETED, "매치 종료 누적", id));
        }
        this.roomJoined = Counter.builder("mirboard.room.joined")
                .description("방 입장 성공 누적 (게임 무관)")
                .register(registry);
        this.actionRejected = Counter.builder("mirboard.action.rejected")
                .description("STOMP 액션 검증 실패 누적 (게임 무관)")
                .register(registry);
    }

    /**
     * <b>{@code .created} 를 쓰면 안 된다 (D-107 실측).</b> OpenMetrics 는 카운터의 생성시각
     * 시계열에 {@code _created} 접미사를 예약해 두었고, Prometheus 클라이언트가 이를 떼어
     * 낸다 — {@code mirboard.room.created} 는 {@code mirboard_room_total} 로 노출돼
     * "만들어진 방 수"가 아니라 "방 수"처럼 읽힌다. {@code .completed} 는 예약어가 아니라
     * 그대로 살아남는 것이 대조 증거다. 그래서 {@code opened} 를 쓴다.
     */
    private static final String ROOM_OPENED = "mirboard.room.opened";
    private static final String GAME_STARTED = "mirboard.game.started";
    private static final String GAME_STARTED_DESC = "게임 시작 (정원 + 전원 준비) 누적";
    private static final String ROUND_COMPLETED = "mirboard.round.completed";
    private static final String MATCH_COMPLETED = "mirboard.match.completed";

    private Counter counter(String name, String description, String gameType) {
        return Counter.builder(name)
                .description(description)
                .tag("gameType", gameType)
                .register(registry);
    }

    /**
     * 등록되지 않은 gameType 이 와도 집계를 잃지 않는다. 정상 경로에서는 발생하지 않지만
     * (방 생성이 {@code GameRegistry.require} 를 이미 통과한다), 조용히 세지 않는 것보다
     * 늦게라도 시계열을 만드는 편이 낫다.
     */
    private void bump(Map<String, Counter> byGame, String name, String description,
                      String gameType) {
        byGame.computeIfAbsent(gameType, g -> counter(name, description, g)).increment();
    }

    public void roomOpened(String gameType) {
        bump(roomOpenedByGame, ROOM_OPENED, "방 생성 누적", gameType);
    }

    public void gameStarted(String gameType) {
        bump(gameStartedByGame, GAME_STARTED, GAME_STARTED_DESC, gameType);
    }

    public void roundCompleted(String gameType) {
        bump(roundCompletedByGame, ROUND_COMPLETED, "라운드 완료 누적", gameType);
    }

    public void matchCompleted(String gameType) {
        bump(matchCompletedByGame, MATCH_COMPLETED, "매치 종료 누적", gameType);
    }

    public void roomJoined() {
        roomJoined.increment();
    }

    public void actionRejected() {
        actionRejected.increment();
    }
}
