package com.mirboard.domain.game.onecard.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 원카드 구현. 인프라는 {@code code()}
 * (= {@code reason().name()}) 만 읽고 {@link RejectionReason} 은 도메인 안에 남는다.
 */
public final class OneCardActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public OneCardActionRejectedException(RejectionReason reason) {
        super(reason.name(), "One Card action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
