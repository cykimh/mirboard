package com.mirboard.infra.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameRegistry;
import com.mirboard.domain.game.core.GameStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-107 — 게임 활동 카운터의 {@code gameType} 차원.
 *
 * <p>전에는 {@code gameStarted} 에 {@code tag("gameType","TICHU")} 가 상수로 박혀 있어
 * 스컬킹 게임이 TICHU 로 집계됐다. <b>틀린 태그는 없는 태그보다 나쁘다</b> — 없으면 못
 * 나누는 걸 알지만, 틀리면 나눈 결과를 믿는다. 이 파일은 그 거짓 집계가 되돌아오지
 * 않도록 고정한다.
 */
class MirboardMetricsTest {

    private static final String TICHU = "TICHU";
    private static final String SKULL_KING = "SKULL_KING";

    private static GameDefinition fake(String id) {
        return new GameDefinition() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 8; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record Fixture(SimpleMeterRegistry registry, MirboardMetrics metrics) {}

    private static Fixture fixture() {
        var registry = new SimpleMeterRegistry();
        var games = new GameRegistry(List.of(fake(TICHU), fake(SKULL_KING)));
        return new Fixture(registry, new MirboardMetrics(registry, games));
    }

    private static double count(SimpleMeterRegistry registry, String name, String gameType) {
        var c = registry.find(name).tag("gameType", gameType).counter();
        return c == null ? -1 : c.count();
    }

    @Test
    @DisplayName("등록된 게임 전체가 기동 시 0 값 시계열로 미리 등록된다")
    void preRegistersEveryGame() {
        var f = fixture();

        // 한 판도 안 돌았어도 스크래퍼가 같은 시계열을 봐야 한다(기존 성질 유지).
        for (String name : List.of("mirboard.room.opened", "mirboard.game.started",
                "mirboard.round.completed", "mirboard.match.completed")) {
            assertThat(count(f.registry(), name, TICHU)).as(name + " TICHU").isEqualTo(0.0);
            assertThat(count(f.registry(), name, SKULL_KING)).as(name + " SK").isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("스컬킹 게임 시작이 TICHU 로 집계되지 않는다 — D-107 이 고친 거짓 집계")
    void gameStartedIsNotHardcodedToTichu() {
        var f = fixture();

        f.metrics().gameStarted(SKULL_KING);

        assertThat(count(f.registry(), "mirboard.game.started", SKULL_KING)).isEqualTo(1.0);
        assertThat(count(f.registry(), "mirboard.game.started", TICHU)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("라운드·매치 완료도 게임별로 갈린다")
    void roundAndMatchAreDimensioned() {
        var f = fixture();

        f.metrics().roundCompleted(SKULL_KING);
        f.metrics().roundCompleted(SKULL_KING);
        f.metrics().roundCompleted(TICHU);
        f.metrics().matchCompleted(TICHU);

        assertThat(count(f.registry(), "mirboard.round.completed", SKULL_KING)).isEqualTo(2.0);
        assertThat(count(f.registry(), "mirboard.round.completed", TICHU)).isEqualTo(1.0);
        assertThat(count(f.registry(), "mirboard.match.completed", TICHU)).isEqualTo(1.0);
        assertThat(count(f.registry(), "mirboard.match.completed", SKULL_KING)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("로비/프로토콜 카운터는 평면 — gameType 태그가 붙지 않는다")
    void lobbyCountersStayFlat() {
        var f = fixture();

        f.metrics().roomJoined();
        f.metrics().actionRejected();

        assertThat(f.registry().find("mirboard.room.joined").counter().count()).isEqualTo(1.0);
        assertThat(f.registry().find("mirboard.action.rejected").counter().count()).isEqualTo(1.0);
        // 태그를 달지 않았으므로 gameType 으로 찾으면 없다.
        assertThat(count(f.registry(), "mirboard.room.joined", TICHU)).isEqualTo(-1);
    }

    @Test
    @DisplayName("등록 안 된 게임이 와도 집계를 잃지 않는다 (늦은 등록)")
    void unknownGameStillCounts() {
        var f = fixture();

        f.metrics().gameStarted("YACHT");

        assertThat(count(f.registry(), "mirboard.game.started", "YACHT")).isEqualTo(1.0);
        // 기존 게임 시계열은 오염되지 않는다.
        assertThat(count(f.registry(), "mirboard.game.started", TICHU)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("같은 게임을 여러 번 세도 카운터 인스턴스는 하나다")
    void reusesCounterInstance() {
        var f = fixture();

        f.metrics().roomOpened(TICHU);
        f.metrics().roomOpened(TICHU);
        f.metrics().roomOpened(TICHU);

        assertThat(f.registry().find("mirboard.room.opened").tag("gameType", TICHU)
                .counters()).hasSize(1);
        assertThat(count(f.registry(), "mirboard.room.opened", TICHU)).isEqualTo(3.0);
    }
}
