package com.mirboard.domain.game.yacht.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 요트 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽어
 * 본인 큐로 {@code ERROR} 를 돌려준다 — 그래서 요트에는 엔진이 만드는 비공개 이벤트가 하나도 없다.
 */
public final class YachtActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public YachtActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Yacht action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
