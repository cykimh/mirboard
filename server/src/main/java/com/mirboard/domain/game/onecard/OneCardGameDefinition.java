package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 원카드의 카탈로그 메타데이터 + 엔진 팩토리 (D-128). {@code GameRegistry} 가 자동 수집한다 — 로비·허브·
 * 디스패치 수정 없이 이 Bean 등록으로 인게임까지 연결된다(D-102 가 실증한 약속).
 *
 * <p><b>공개 상태는 설정이다</b>({@code mirboard.onecard.status}, 기본 {@code COMING_SOON}). 클라 게임판(S4)
 * 전에는 카탈로그에 "준비 중"으로만 보이고 방을 만들 수 없다({@code RoomService} 가 AVAILABLE 만 허용).
 * 통합 테스트는 AVAILABLE 로 켜서 방을 만든다.
 *
 * <p>경쟁 창 길이와 봇 반응 구간도 설정에서 읽는다(룰 §9). 슬롯 수는 클라와 맞춘 프로토콜 상수라 열지 않는다.
 * {@code supportedRoomOptions()} 는 재정의하지 않는다 — 목표 점수·팀·내기를 쓰지 않는 개인전이다(D-106).
 */
@Component
public final class OneCardGameDefinition implements GameDefinition {

    public static final String ID = "ONE_CARD";

    private final OneCardStateStore stateStore;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final GameStatus status;
    private final RaceSettings raceSettings;
    private final SecureRandom random = new SecureRandom();

    public OneCardGameDefinition(
            OneCardStateStore stateStore,
            Clock clock,
            ApplicationEventPublisher publisher,
            @Value("${mirboard.onecard.status:COMING_SOON}") GameStatus status,
            @Value("${mirboard.onecard.race-window-millis:3000}") long raceWindowMillis,
            @Value("${mirboard.onecard.bot-reaction-owner-min-millis:1000}") long ownerMinMillis,
            @Value("${mirboard.onecard.bot-reaction-owner-max-millis:2500}") long ownerMaxMillis,
            @Value("${mirboard.onecard.bot-reaction-catcher-min-millis:1000}") long catcherMinMillis,
            @Value("${mirboard.onecard.bot-reaction-catcher-max-millis:2500}") long catcherMaxMillis) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.publisher = publisher;
        // 환경 변수가 빈 값이면 스프링의 enum 변환이 null 을 준다 — 카탈로그 정렬에서 원인 모를 NPE 로 멈추기 전에 여기서 밝힌다.
        this.status = Objects.requireNonNull(status,
                "mirboard.onecard.status(환경 변수 MIRBOARD_ONECARD_STATUS)가 비어 있다 — "
                        + "AVAILABLE, COMING_SOON, DISABLED 중 하나여야 한다");
        this.raceSettings = new RaceSettings(raceWindowMillis, ownerMinMillis, ownerMaxMillis,
                catcherMinMillis, catcherMaxMillis, RaceSettings.DEFAULT.slotCount());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "원카드";
    }

    @Override
    public String shortDescription() {
        return "2~6인 손패 털기. 공격을 쌓아 넘기고, 한 장 남으면 누구보다 먼저 \"원카드!\"를 외친다.";
    }

    @Override
    public int minPlayers() {
        return 2;
    }

    @Override
    public int maxPlayers() {
        return 6;
    }

    @Override
    public GameStatus status() {
        return status;
    }

    /**
     * 설정에서 만든 경쟁 창 설정 — 엔진 어댑터({@link OneCardGameEngine})가 쓴다. 라운드 시작
     * ({@code OneCardRoundStarter})은 쓰지 않는다: 시작에는 경쟁 창이 없어 스타터는 {@link RaceSettings#DEFAULT} 로
     * 엔진을 만들고, {@code startMatch} 는 설정을 읽지 않는다.
     */
    public RaceSettings raceSettings() {
        return raceSettings;
    }

    @Override
    public GameEngine newEngine(GameContext ctx) {
        return new OneCardGameEngine(ctx, stateStore, clock, random, raceSettings, publisher);
    }
}
