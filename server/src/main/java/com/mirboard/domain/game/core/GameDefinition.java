package com.mirboard.domain.game.core;

/**
 * 카탈로그에 노출되는 게임의 정적 메타데이터 + 엔진 팩토리. 새 게임을 추가할 때는
 * 본 인터페이스를 구현한 클래스를 @Component 로 만들면 된다. {@link GameRegistry} 가
 * Spring DI 로 자동 수집한다. (의도적으로 non-sealed: 게임 추가 절차의 friction을
 * 줄이고 테스트에서 fake 정의를 만들 수 있도록.)
 */
public interface GameDefinition {

    /** ID 는 영문 대문자 스네이크. 예: "TICHU". */
    String id();

    /** UI에 표시될 이름. 예: "티츄". */
    String displayName();

    /** 한두 문장 소개. */
    String shortDescription();

    int minPlayers();

    int maxPlayers();

    GameStatus status();

    /** Phase 3 에서 게임 시작 시 호출. 현재는 미구현 게임이면 throws. */
    GameEngine newEngine(GameContext ctx);
}
