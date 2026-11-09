package com.mirboard.domain.game.cloudroute.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 구름항로 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽는다.
 */
public final class CloudRouteActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public CloudRouteActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Cloud Route action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
