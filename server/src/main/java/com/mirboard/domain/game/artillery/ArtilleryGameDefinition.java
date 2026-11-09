package com.mirboard.domain.game.artillery;

import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 씨앗포의 카탈로그 메타데이터 + 엔진 팩토리 (S2, D-220~D-222). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정
 * 없이 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.artillery.status} = 환경 변수 {@code MIRBOARD_ARTILLERY_STATUS}). 완성 전 단계는
 * 기본 {@code DISABLED}(카탈로그 비노출 — 관제 규칙), 클라 게임판이 완성되는 단계(S3)에서 기본을 {@code AVAILABLE} 로 바꾼다. 기본값은
 * {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고 같아야 한다. 방 옵션은 쓰지 않는다(D-106 옵트인 기본).
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
                                   @Value("${mirboard.artillery.status:DISABLED}") GameStatus status,
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
        return "2~4인 포격 게임. 각도·힘·바람을 읽어 씨앗을 쏴 상대 화분을 구름 바다로 떨어뜨린다.";
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

    /** 라운드 시작이 같은 연출 상한으로 시작하도록. */
    public ArtilleryEngine rules() {
        return rules;
    }
}
