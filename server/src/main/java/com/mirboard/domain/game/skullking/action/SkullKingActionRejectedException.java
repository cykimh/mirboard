package com.mirboard.domain.game.skullking.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 스컬킹 구현. 인프라는 {@code code()}
 * (= {@code reason().name()}) 만 읽고 {@link RejectionReason} enum 은 도메인 안에 남는다
 * (D-98 과 같은 구조).
 */
public final class SkullKingActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public SkullKingActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Skull King action rejected: " + reason);
        this.reason = reason;
    }

    public SkullKingActionRejectedException(RejectionReason reason, String detail) {
        super(reason.name(), "Skull King action rejected: " + reason + " — " + detail);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
