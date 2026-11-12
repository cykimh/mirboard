package com.mirboard.domain.game.yacht;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.yacht.persistence.YachtStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 얏추(코드 이름 요트/YACHT — D-207 표시 이름만 바꿈)의 카탈로그 메타데이터 + 엔진 팩토리 (S3, D-181~D-183). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정
 * 없이 이 Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.yacht.status} = 환경 변수 {@code MIRBOARD_YACHT_STATUS}, 기본
 * {@code AVAILABLE} — D-200 공개. 완성 전 단계에서는 {@code DISABLED} 였다). {@code COMING_SOON} 을 주면 카탈로그에 "준비 중"으로만
 * 보이고 새 방을 만들 수 없다(진행 중인 판은 끝까지) — 운영의 되돌리기 손잡이다({@code docs/deploy.md}). 기본값은 {@code application.yml} 과 아래 {@code @Value}
 * 두 곳에 있고 같아야 한다. 방 옵션은 진행 방식 하나(D-202 — 목표 점수·팀·판돈 없음).
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
                               @Value("${mirboard.yacht.status:AVAILABLE}") GameStatus status) {
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

    /** D-207 — 화면에 보이는 이름은 "얏추". 코드 이름(`YACHT`·패키지·테이블·환경 변수)은 그대로다. */
    @Override
    public String displayName() {
        return "얏추";
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

    /** D-202 — 진행 방식(차례대로 / 모두 같이)만. 목표 점수·팀·판돈은 없다. */
    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.TURN_MODE);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new YachtGameEngine(ctx, stateStore, clock, random, publisher);
    }
}
