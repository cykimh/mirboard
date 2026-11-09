package com.mirboard.domain.game.yacht;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 요트의 카탈로그 메타데이터 + 엔진 팩토리 (S3, D-181~D-183). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정
 * 없이 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.yacht.status} = 환경 변수 {@code MIRBOARD_YACHT_STATUS}, 기본
 * {@code DISABLED} — 단계마다 main 에 들어가므로 완성 전에는 카탈로그에도 보이지 않게 둔다(관제 규칙, 2026-11-09).
 * 테스트·로컬 확인은 설정으로 연다. 공개는 S5). 기본값은 {@code application.yml} 과 아래 {@code @Value}
 * 두 곳에 있고 같아야 한다. 방 옵션은 쓰지 않는다(목표 점수·팀·판돈 없음 — D-106 옵트인 기본).
 */
@Component
public final class YachtGameDefinition implements GameDefinition {

    public static final String ID = "YACHT";

    private final YachtStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final SecureRandom random = new SecureRandom();

    public YachtGameDefinition(YachtStateStore stateStore,
                               Clock clock,
                               ApplicationEventPublisher publisher,
                               @Value("${mirboard.yacht.status:DISABLED}") GameStatus status) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.yacht.status(환경 변수 MIRBOARD_YACHT_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "요트";
    }

    @Override
    public String shortDescription() {
        return "1~4인 주사위 게임. 주사위 5개를 세 번까지 굴려 12칸 점수표를 채운다.";
    }

    /** D-181 — 혼자 하기 허용(전적은 남기지 않는다). */
    @Override
    public int minPlayers() {
        return 1;
    }

    /** D-181 — 순차 진행이라 기다림이 길어지지 않게 4명까지. */
    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /** D-182 — 차례 전체 45초. 차례가 굴림 3번 + 기록이라 한 수짜리 게임(원카드 30초)보다 길게. */
    @Override
    public int defaultTurnSeconds() {
        return 45;
    }

    @Override
    public List<Integer> turnSecondsChoices() {
        return List.of(0, 20, 30, 45, 60);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new YachtGameEngine(ctx, stateStore, clock, random, publisher);
    }
}
