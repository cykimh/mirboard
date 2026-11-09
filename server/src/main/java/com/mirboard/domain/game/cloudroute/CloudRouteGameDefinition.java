package com.mirboard.domain.game.cloudroute;

import com.mirboard.domain.game.cloudroute.persistence.CloudRouteStateStore;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 구름항로의 카탈로그 메타데이터 + 엔진 팩토리 (D-212). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정 없이 이 Bean
 * 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.cloudroute.status} = 환경 변수 {@code MIRBOARD_CLOUDROUTE_STATUS}, 기본
 * {@code AVAILABLE} — D-214 공개). S2·S3 동안은 기본 {@code DISABLED} 였다(관제 규칙 — 완성 전 카탈로그 비노출). 되돌리기는
 * {@code COMING_SOON}(새 방만 막고 진행 중인 판은 끝까지 — `docs/deploy.md`). {@code supportedRoomOptions()} 는 빈 집합(기본) — 목표 점수·팀·내기를 쓰지 않는 개인전이다(D-106).
 */
@Component
public final class CloudRouteGameDefinition implements GameDefinition {

    public static final String ID = "CLOUD_ROUTE";

    private final CloudRouteStateStore stateStore;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final SecureRandom random = new SecureRandom();

    public CloudRouteGameDefinition(CloudRouteStateStore stateStore,
                                    ApplicationEventPublisher publisher,
                                    @Value("${mirboard.cloudroute.status:AVAILABLE}") GameStatus status) {
        this.stateStore = stateStore;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.cloudroute.status(환경 변수 MIRBOARD_CLOUDROUTE_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "구름항로";
    }

    @Override
    public String shortDescription() {
        return "2~4인 하늘섬 무역. 주사위로 섬을 돌며 터를 사고 풍등탑을 세워 통행료를 받는다.";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 4;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /** 결정마다 다시 세는 턴 제한 — 끔이면 자리를 비운 한 명이 판을 멈춘다(설계서 §6.2). */
    @Override
    public int defaultTurnSeconds() {
        return 30;
    }

    @Override
    public List<Integer> turnSecondsChoices() {
        return List.of(0, 15, 30, 60);
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new CloudRouteGameEngine(ctx, stateStore, random, publisher);
    }
}
