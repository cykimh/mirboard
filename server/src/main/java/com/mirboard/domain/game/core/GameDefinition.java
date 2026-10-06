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

    /**
     * 정상 종료한 매치 뒤에 같은 테이블에서 '한 판 더'(리매치, D-82)를 지원하는가 (D-122).
     *
     * <p>지원하면 사람만의 매치는 끝나도 방을 IN_GAME 으로 붙잡아 둔다 — 호스트가 같은 좌석으로
     * 새 매치를 시작할 수 있게. 지원하지 않으면 정상 종료 때 방이 FINISHED 로 넘어간다(봇이
     * 낀 매치는 원래부터 FINISHED). 리매치 UI 가 없는 게임이 IN_GAME 으로 남으면, 끝난 뒤의
     * '나가기'가 탈주 판정을 거쳐 좌석 목록을 당기는 경로로 흘렀다.
     *
     * <p><b>기본은 false — 옵트인이다</b>({@link #supportedRoomOptions()} 와 같은 원칙). 새
     * 게임은 한 줄도 쓰지 않아도 끝난 방이 정리된다.
     */
    default boolean supportsRematch() {
        return false;
    }

    /**
     * D-130 — 방 만들기 모달이 처음 고르는 인원. <b>기본은 {@link #maxPlayers()}</b> — 지금까지의 동작 그대로라 재정의하지
     * 않은 게임은 바뀌지 않는다. {@code minPlayers()..maxPlayers()} 안이어야 한다.
     *
     * <p>클라의 처음 선택일 뿐이다 — 서버의 capacity 생략 기본({@code RoomService}, maxPlayers)은 따로다. 인원 가변
     * 게임은 클라가 늘 capacity 를 보내므로 둘이 실제로 갈리지 않는다.
     */
    default int defaultPlayers() {
        return maxPlayers();
    }

    /**
     * D-130 — 방 만들기 모달이 처음 고르는 턴 제한(초). <b>기본은 0(끔)</b> — 서버 기본
     * ({@code RoomService.DEFAULT_TURN_SECONDS})과 같다. 모달의 선택지(0·30·60·90) 중 하나여야 처음부터 선택돼 보인다.
     */
    default int defaultTurnSeconds() {
        return 0;
    }

    /** Phase 3 에서 게임 시작 시 호출. 현재는 미구현 게임이면 throws. */
    GameEngine newEngine(GameContext ctx);
}
