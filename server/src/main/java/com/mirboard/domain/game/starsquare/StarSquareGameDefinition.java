package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.starsquare.persistence.StarSquareStateStore;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 별자리 수판의 카탈로그 메타데이터 + 엔진 팩토리(S2, D-282~D-285). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정 없이
 * 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.starsquare.status} = 환경 변수 {@code MIRBOARD_STAR_SQUARE_STATUS}, 기본
 * {@code DISABLED} — 카탈로그에도 없다. S4 에서 {@code AVAILABLE}). 기본값은 {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고
 * 같아야 한다.
 *
 * <p>방 옵션: 판 길이(퍼즐 수 1·3·5)·봇 강도·난이도. 턴 제한은 <b>퍼즐 제한</b>이다 — 0 = 자동(난이도별 권장 제한, 기본), 그 밖은 분 단위.
 */
@Component
public final class StarSquareGameDefinition implements GameDefinition {

    public static final String ID = "STAR_SQUARE";

    private final StarSquareStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final double timeScale;

    public StarSquareGameDefinition(StarSquareStateStore stateStore,
                                    Clock clock,
                                    ApplicationEventPublisher publisher,
                                    @Value("${mirboard.starsquare.status:DISABLED}") GameStatus status,
                                    @Value("${mirboard.starsquare.time-scale:1.0}") double timeScale) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.starsquare.status(환경 변수 MIRBOARD_STAR_SQUARE_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
        this.timeScale = timeScale;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "별자리 수판";
    }

    @Override
    public String shortDescription() {
        return "1~4인 마방진 레이스. 같은 퍼즐을 동시에 풀어 가로·세로·대각선 합을 먼저 맞춘다.";
    }

    /** 혼자 하기 허용(기록은 남기지 않는다 — D-181 선례). */
    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /** 퍼즐 제한 "자동"(난이도별 — 룰 §9-2). */
    @Override
    public int defaultTurnSeconds() {
        return 0;
    }

    /** 퍼즐 제한: 자동·3·5·8·10·15·20분(D-285). */
    @Override
    public List<Integer> turnSecondsChoices() {
        return List.of(0, 180, 300, 480, 600, 900, 1_200);
    }

    /** D-286 — 모두가 같은 퍼즐을 동시에 푼다 — 시간은 퍼즐 하나에 걸린다. */
    @Override
    public String turnLimitLabel() {
        return "퍼즐 제한";
    }

    /** D-286 — 0 은 끔이 아니라 난이도별 권장 제한(쉬움 8분·보통 7분·어려움 20분). */
    @Override
    public String turnLimitZeroLabel() {
        return "자동";
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.BOT_LEVEL, RoomOption.MATCH_LENGTH, RoomOption.DIFFICULTY);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new StarSquareGameEngine(ctx, StarSquareConfig.from(ctx, timeScale), stateStore, clock, publisher);
    }

    public double timeScale() {
        return timeScale;
    }
}
