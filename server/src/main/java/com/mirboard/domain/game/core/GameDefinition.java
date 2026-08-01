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

    /**
     * 이 게임이 실제로 쓰는 방 설정 (D-106). 방 생성 UI·대기실이 이걸 보고 무엇을 노출할지
     * 정하고, 서버는 미지원 옵션에 기본값 아닌 값이 오면 거절한다.
     *
     * <p><b>기본은 빈 집합 — 옵트인이다.</b> 새 게임은 한 줄도 쓰지 않아도 자기가 안 쓰는
     * 설정이 화면에 뜨지 않는다. "새 게임 = 패키지 + `GameDefinition` Bean"(D-102) 약속을
     * 깨지 않으려는 것이고, 반대 방향(기본 전체 허용)이면 새 게임마다 이 결함이 재생산된다.
     */
    default java.util.Set<RoomOption> supportedRoomOptions() {
        return java.util.EnumSet.noneOf(RoomOption.class);
    }

    /** Phase 3 에서 게임 시작 시 호출. 현재는 미구현 게임이면 throws. */
    GameEngine newEngine(GameContext ctx);
}
