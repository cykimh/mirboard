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
}
