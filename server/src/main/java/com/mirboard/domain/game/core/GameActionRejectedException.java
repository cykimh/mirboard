package com.mirboard.domain.game.core;

/**
 * 룰 위반으로 액션이 거절되었음을 알리는 예외. 게임별 예외(예:
 * {@code TichuActionRejectedException})가 본 클래스를 확장하고, 인프라는 {@link #code()}
 * 만 읽어 클라에게 `ERROR` 로 되쏜다 — 거절 사유 enum 자체는 게임 내부 관심사다.
 *
 * <p>비-sealed 인 것은 의도적: 새 게임이 자기 사유 enum 을 들고 확장할 수 있어야 한다.
 */
public class GameActionRejectedException extends RuntimeException {

    private final String code;

    public GameActionRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 클라에 전달할 안정 식별자 (STOMP `ERROR` envelope 의 `code`). */
    public String code() {
        return code;
    }
}
