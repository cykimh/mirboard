package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.dambaduk.persistence.DambadukStateStore;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 담바둑의 카탈로그 메타데이터 + 엔진 팩토리 (D-274·D-278). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·디스패치 수정 없이 이
 * Bean 등록으로 인게임까지 연결된다.
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.dambaduk.status} = {@code MIRBOARD_DAMBADUK_STATUS}, 기본 {@code AVAILABLE} — D-281 공개.
 * S4 전까지는 {@code DISABLED} 였다). 되돌리기는 운영 시크릿으로 {@code COMING_SOON}·{@code DISABLED}({@code docs/deploy.md}). 기본값은
 * {@code application.yml} 과 아래 {@code @Value} 두 곳에 있고 같아야 한다({@code DambadukGameDefinitionTest}).
 *
 * <p>2~3인 개인전, 방 설정은 봇 난이도({@code BOT_LEVEL}) 하나 — 목표 점수·팀·내기를 쓰지 않는다(D-106). 담 넘기는 인원에 묶여 옵션이
 * 아니다. 턴 제한은 끔·30·60·90, 기본 60초(룰 §8, W6).
 */
@Component
public final class DambadukGameDefinition implements GameDefinition {

    public static final String ID = "DAMBADUK";

    private final DambadukStateStore stateStore;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;

    public DambadukGameDefinition(DambadukStateStore stateStore,
                                  ApplicationEventPublisher publisher,
                                  @Value("${mirboard.dambaduk.status:AVAILABLE}") GameStatus status) {
        this.stateStore = stateStore;
        this.publisher = publisher;
        this.status = Objects.requireNonNull(status,
                "mirboard.dambaduk.status(환경 변수 MIRBOARD_DAMBADUK_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "담바둑";
    }

    @Override
    public String shortDescription() {
        return "7×7 나무판에서 돌을 옮기고 담을 하나씩 쌓아, 내 돌만 있는 집을 가장 넓게 두른다. 2~3인.";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 3;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    @Override
    public Set<RoomOption> supportedRoomOptions() {
        return EnumSet.of(RoomOption.BOT_LEVEL);
    }

    /** 끔이면 자리를 비운 사람을 남은 사람이 무기한 기다린다 — 기본은 60초(룰 §8). 선택지는 기본(끔·30·60·90) 그대로. */
    @Override
    public int defaultTurnSeconds() {
        return 60;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new DambadukGameEngine(ctx, stateStore, publisher);
    }
}
