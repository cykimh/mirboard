package com.mirboard.domain.game.tichu.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * D-98: 포트 예외 {@link GameActionRejectedException} 를 확장한다. 인프라는 {@code code()}
 * (= {@code reason().name()}) 만 읽고, {@link RejectionReason} enum 은 티츄 내부에 남는다.
 */
public final class TichuActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public TichuActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Tichu action rejected: " + reason);
        this.reason = reason;
    }

    public TichuActionRejectedException(RejectionReason reason, String detail) {
        super(reason.name(), "Tichu action rejected: " + reason + " — " + detail);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
