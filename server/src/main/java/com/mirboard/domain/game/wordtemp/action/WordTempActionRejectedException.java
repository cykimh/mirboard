package com.mirboard.domain.game.wordtemp.action;

import com.mirboard.domain.game.core.GameActionRejectedException;

/**
 * 포트 예외 {@link GameActionRejectedException} 의 낱말 온도 구현. 인프라는 {@code code()}(= {@code reason().name()}) 만 읽어 본인 큐로
 * {@code ERROR} 를 돌려준다. 거절은 상태를 바꾸지 않는다(힌트 개수도 줄지 않는다 — §5.2).
 */
public final class WordTempActionRejectedException extends GameActionRejectedException {

    private final RejectionReason reason;

    public WordTempActionRejectedException(RejectionReason reason) {
        super(reason.name(), "Word temperature action rejected: " + reason);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
