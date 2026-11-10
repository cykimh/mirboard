package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 씨앗포의 카탈로그 메타데이터 + 엔진 팩토리 (S2, D-220~D-222). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정
 * 없이 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.artillery.status} = 환경 변수 {@code MIRBOARD_ARTILLERY_STATUS}, 기본 {@code AVAILABLE}
 * — D-223 공개. 완성 전 단계(S1·S2)에서는 {@code DISABLED} 였다). {@code COMING_SOON} 이면 카탈로그에 "준비 중"으로만 보이고 새 방을 만들 수 없다
 * (진행 중인 판은 끝까지) — 운영의 되돌리기 손잡이다({@code docs/deploy.md}). 기본값은
 * {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고 같아야 한다.
 *
 * <p>방 설정은 하나(D-225): 모드({@code TEAM_MODE} — 개인전 2~4인 / 2:2 팀전 정확히 4인, 팀 = 좌석 홀짝). {@code TEAMS} 는 늘 팀인 게임의
 * 라벨이라 쓰지 않는다 — 고를 수 있는 팀전은 오목처럼 {@code TEAM_MODE} 다(팀전이면 대기실 라벨이 "팀 배정").
 *
 * <p>엔진은 2~6인을 검증했지만 MVP 공개 상한은 4인이다(D-220 — 한 바퀴 대기 시간).
 */
@Component
public final class ArtilleryGameDefinition implements GameDefinition {

    public static final String ID = "ARTILLERY";

    private final ArtilleryStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final ArtilleryEngine rules;

    public ArtilleryGameDefinition(ArtilleryStateStore stateStore,
                                   Clock clock,
                                   ApplicationEventPublisher publisher,
                                   @Value("${mirboard.artillery.status:AVAILABLE}") GameStatus status,
                                   @Value("${mirboard.artillery.resolve-cap-millis:18000}") long resolveCapMillis) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.artillery.status(환경 변수 MIRBOARD_ARTILLERY_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
        this.rules = new ArtilleryEngine(resolveCapMillis);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "씨앗포";
    }

    @Override
    public String shortDescription() {
        return "2~4인 포격 게임(2:2 팀전도). 각도·힘·바람을 읽어 씨앗을 쏴 상대 화분을 구름 바다로 떨어뜨린다.";
    }

    @Override
    public int minPlayers() {
        return ArtilleryEngine.MIN_PLAYERS;
    }

    /** D-220 — 엔진은 6인까지 되지만 한 바퀴 대기가 길어 4인까지 연다. */
    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.TEAM_MODE);
    }

    /** 정원은 두 모드 모두 4(개인전은 2명부터 시작, 팀전은 4명이 다 차야 시작). */
    @Override
    public int capacityFor(Map<RoomOption, String> options) {
        return maxPlayers();
    }

    /** D-225 — 2:2 를 3명으로 시작하지 않는다. */
    @Override
    public int playersToStart(Map<RoomOption, String> options) {
        return teamsOf(options) ? 4 : minPlayers();
    }

    /** 방 설정의 모드가 2:2 팀전인가. */
    public static boolean teamsOf(Map<RoomOption, String> options) {
        return options != null && "TEAMS_2V2".equals(options.get(RoomOption.TEAM_MODE));
    }

    @Override
    public int defaultTurnSeconds() {
        return 30;
    }

    /** D-220 — '끔' 없음: 끊긴 사람 차례에 판 전체가 멈추지 않게. */
    @Override
    public List<Integer> turnSecondsChoices() {
        return List.of(20, 30, 45, 60);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new ArtilleryGameEngine(ctx, rules, stateStore, clock, publisher);
    }

    /** 라운드 시작이 고르기 마감을 같은 시계로 정하도록 (D-224). */
    public Clock clock() {
        return clock;
    }

    public ArtilleryEngine rules() {
        return rules;
    }
}
