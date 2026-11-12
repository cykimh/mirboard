package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.omok.persistence.OmokStateStore;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 오목의 카탈로그 메타데이터 + 엔진 팩토리 (D-175). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정 없이 이
 * Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.omok.status} = {@code MIRBOARD_OMOK_STATUS}, 기본 {@code AVAILABLE} — D-190 공개,
 * 게임판이 완성되는 대로 공개한다는 사용자 결정). 되돌리기는 운영 시크릿으로 {@code COMING_SOON}·{@code DISABLED}({@code docs/deploy.md}).
 * 기본값은 {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고 같아야 한다.
 *
 * <p>방 설정은 셋(D-176): 모드({@code TEAM_MODE} — 1:1 정원 2 / 2:2 정원 4), 룰({@code OMOK_RULE}), 봇 난이도
 * ({@code BOT_LEVEL}). 정원과 시작 인원은 모드가 정한다 — 2:2 를 3명으로 시작할 수 없다.
 */
@Component
public final class OmokGameDefinition implements GameDefinition {

    public static final String ID = "OMOK";

    private final OmokStateStore stateStore;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;

    public OmokGameDefinition(OmokStateStore stateStore,
                              ApplicationEventPublisher publisher,
                              @Value("${mirboard.omok.status:AVAILABLE}") GameStatus status) {
        this.stateStore = stateStore;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.omok.status(환경 변수 MIRBOARD_OMOK_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "오목";
    }

    @Override
    public String shortDescription() {
        return "19×19 바둑판에 다섯 개를 먼저 잇는다. 1:1 이나 2:2 팀전(같은 팀이 같은 색을 번갈아), 렌주 금수를 고를 수 있다.";
    }

    @Override
    public int minPlayers() {
        return OmokMode.SOLO.seatCount();
    }

    @Override
    public int maxPlayers() {
        return OmokMode.TEAMS_2V2.seatCount();
    }

    @Override
    public GameStatus status() {
        return status;
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.TEAM_MODE, RoomOption.OMOK_RULE, RoomOption.BOT_LEVEL);
    }

    /** 기본 모드가 1:1 이라 처음 정원도 2. */
    @Override
    public int defaultPlayers() {
        return OmokMode.SOLO.seatCount();
    }

    /** 정원 = 모드의 좌석 수 (D-176 E2). */
    @Override
    public int capacityFor(Map<RoomOption, String> options) {
        return OmokSettings.from(options).mode().seatCount();
    }

    /** 시작 인원도 모드의 좌석 수 — 정원과 같아 "그 이상"이 곧 "정확히"다. 2:2 를 3명으로 시작하지 않는다. */
    @Override
    public int playersToStart(Map<RoomOption, String> options) {
        return capacityFor(options);
    }

    /** 끔이면 자리를 비운 사람을 남은 사람이 무기한 기다린다 — 기본은 30초(D-178). */
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
        return new OmokGameEngine(ctx, stateStore, publisher);
    }
}
