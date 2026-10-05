package com.mirboard.domain.game.core;

/**
 * 게임이 발행하는 이벤트를 묶는 인터페이스. 각 게임 도메인이 자체적으로 sealed
 * 계층(예: TichuEvent)을 정의하고 본 인터페이스를 확장한다.
 *
 * <p>D-98: 브로드캐스터가 게임을 모른 채 라우팅할 수 있을 만큼만 노출한다 — envelope
 * `type` 문자열과 "누구에게만 보낼 것인가". 이벤트 <b>내용</b>은 Jackson 이 런타임 타입
 * 그대로 직렬화하므로 포트가 알 필요가 없다.
 */
public interface GameEvent {

    /** envelope `type` 필드용 안정 식별자. 게임별 `@JsonSubTypes` 이름과 일치시킨다. */
    String envelopeType();

    /**
     * 본인에게만 보낼 이벤트면 대상 좌석, 전체 공개면 -1.
     *
     * <p>D-01 State Hiding 의 라우팅 근거다. 기본값이 "공개"인 것은 의도적 — 비공개는
     * 게임이 명시적으로 선언해야 하고, 그래야 새 이벤트를 추가했을 때 기본 동작이
     * "손패가 토픽으로 샌다"가 아니라 "공개 정보를 공개한다"가 된다.
     */
    default int privateSeat() {
        return -1;
    }

    /** 비공개 이벤트 여부 — `/user/queue` 로만 보낼 것인가. */
    default boolean isPrivate() {
        return privateSeat() >= 0;
    }

    /**
     * false 면 envelope 에 seq 를 붙이지 않는다(방 순번을 소비하지 않음). 기본 true — 기존 게임
     * 동작 그대로.
     *
     * <p>D-126: 방 순번은 클라가 <b>공개 토픽</b>에서 구멍을 찾는 기준이다. 비공개 이벤트가 순번을
     * 쓰면 그 이벤트를 받지 않는 클라에게 다음 공개 이벤트가 항상 구멍으로 보여 resync 를 부른다
     * (티츄는 카드를 낼 때마다 4명 전원이 resync 했다). 그래서 비공개 이벤트는 false 로 두는 것이
     * 맞지만, 기본값을 바꾸면 그 resync 에 기대고 있는 게임의 동작이 바뀌므로 게임이 옵트인한다.
     */
    default boolean sequenced() {
        return true;
    }
}
