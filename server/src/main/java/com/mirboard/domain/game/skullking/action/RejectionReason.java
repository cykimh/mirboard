package com.mirboard.domain.game.skullking.action;

/**
 * 액션 거절 사유. {@code name()} 이 그대로 STOMP {@code ERROR} envelope 의 {@code code}
 * 가 되므로 (D-98) 값 이름은 클라와의 계약이다 — 바꾸면 클라도 함께 고쳐야 한다.
 */
public enum RejectionReason {

    /** 입찰 단계가 아닌데 PLACE_BID 가 왔다. */
    NOT_IN_BIDDING_PHASE,

    /** 플레이 단계가 아닌데 PLAY_CARD 가 왔다. */
    NOT_IN_PLAYING_PHASE,

    /** 이미 예측을 제출했다 — 변경 불가 (§5). */
    ALREADY_BID,

    /** 예측이 0 ~ handSize 범위 밖 (§5, §13-⑪). */
    BID_OUT_OF_RANGE,

    /** 본인 차례가 아니다. */
    NOT_YOUR_TURN,

    /** 손패에 없는 카드를 냈다. */
    CARD_NOT_OWNED,

    /** 티그리스인데 선언이 없거나, 티그리스가 아닌데 선언을 실었다. */
    INVALID_TIGRESS_DECLARATION,

    /** 리드 수트를 손에 들고 있으면서 다른 색상 카드를 냈다 (§6.2). */
    MUST_FOLLOW_LEAD_SUIT,

    /** 라운드가 이미 끝난 상태라 어떤 액션도 받지 않는다. */
    INVALID_STATE_FOR_ACTION
}
