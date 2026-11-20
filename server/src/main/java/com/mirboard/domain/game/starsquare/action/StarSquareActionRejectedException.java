package com.mirboard.domain.game.starsquare.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 별자리 수판 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽어 본인 큐로
 * {@code ERROR} 를 돌려준다 — 그래서 엔진이 만드는 비공개 이벤트는 하나도 없다(§11.2).
 */
public final class StarSquareActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public StarSquareActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Star square action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
