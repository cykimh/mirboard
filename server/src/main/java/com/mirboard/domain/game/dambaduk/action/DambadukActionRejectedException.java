package com.mirboard.domain.game.dambaduk.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/** 포트 예외의 담바둑 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽는다. */
public final class DambadukActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public DambadukActionRejectedException(RejectionReason reason, String detail) {
        super(reason.name(), "Dambaduk action rejected: " + reason + (detail == null ? "" : " (" + detail + ")"));
        this.reason = reason;
    }

    public DambadukActionRejectedException(RejectionReason reason) {
        this(reason, null);
    }

    public RejectionReason reason() {
        return reason;
    }
}
