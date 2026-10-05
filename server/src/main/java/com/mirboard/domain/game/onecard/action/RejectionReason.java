package com.mirboard.domain.game.onecard.action;

/**
 * 액션 거절 사유. {@code name()} 이 그대로 STOMP {@code ERROR} envelope 의 {@code code} 가 되므로 값 이름은
 * 클라와의 계약이다 — 바꾸면 클라도 함께 고쳐야 한다(스컬킹과 같은 규약).
 */
public enum RejectionReason {

    /** 매치가 이미 끝났다. */
    MATCH_OVER,

    /** 탈락(파산·탈주)한 좌석의 액션 (§10). */
    PLAYER_ELIMINATED,

    /** 경쟁 창이 열린 동안의 내기·먹기 (§9-2). */
    RACE_IN_PROGRESS,

    /** 본인 차례가 아니다. */
    NOT_YOUR_TURN,

    /** 손패에 없는 카드. */
    CARD_NOT_OWNED,

    /** 7 인데 무늬를 지정하지 않았거나, 7 이 아닌데 지정했다 (§8.1). */
    INVALID_SUIT_DECLARATION,

    /** 공격받는 중이 아닌데 맨 위와 맞지 않는 카드 (§5.2). */
    CARD_NOT_PLAYABLE,

    /** 공격받는 중인데 반격 조건을 지키는 공격 카드가 아니다 (§5.3, §6.2). */
    COUNTER_REQUIRED,

    /** 열린 경쟁 창이 없거나 창 번호가 다르다 (§9-5). */
    NO_RACE,

    /** "원카드!" 는 창 주인만 누른다. */
    NOT_RACE_OWNER,

    /** 창 주인은 "잡기!" 를 누를 수 없다. */
    OWNER_CANNOT_CATCH
}
