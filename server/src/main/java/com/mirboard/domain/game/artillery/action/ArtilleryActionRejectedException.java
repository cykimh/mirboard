package com.mirboard.domain.game.artillery.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/** 규칙 위반 — 상태 무변경. 인프라가 code 로 본인 큐 {@code ERROR} 를 보낸다. */
public final class ArtilleryActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public ArtilleryActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Artillery action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
