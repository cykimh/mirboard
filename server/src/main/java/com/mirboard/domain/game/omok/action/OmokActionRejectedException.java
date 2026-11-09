package com.mirboard.domain.game.omok.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/** 포트 예외의 오목 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽는다. */
public final class OmokActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public OmokActionRejectedException(RejectionReason reason, String detail) {
        super(reason.name(), "Omok action rejected: " + reason + (detail == null ? "" : " (" + detail + ")"));
        this.reason = reason;
    }

    public OmokActionRejectedException(RejectionReason reason) {
        this(reason, null);
    }

    public RejectionReason reason() {
        return reason;
    }
}
