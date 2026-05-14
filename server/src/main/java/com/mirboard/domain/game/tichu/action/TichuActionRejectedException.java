package com.mirboard.domain.game.tichu.action;

public final class TichuActionRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public TichuActionRejectedException(RejectionReason reason) {
        super("Tichu action rejected: " + reason);
        this.reason = reason;
    }

    public TichuActionRejectedException(RejectionReason reason, String detail) {
        super("Tichu action rejected: " + reason + " — " + detail);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
