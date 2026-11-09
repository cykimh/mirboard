package com.mirboard.domain.game.artillery.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.core.GameAction;

/**
 * 씨앗포 액션 (`docs/rules-artillery.md` §4). 차례당 하나 — 이동은 {@link Fire#moveDx} 로 발사에 싣는다(D-220).
 * 판별자는 {@code @action}(다른 게임과 같은 STOMP 모양).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ArtilleryAction.Fire.class, name = "FIRE"),
        @JsonSubTypes.Type(value = ArtilleryAction.Skip.class, name = "SKIP")
})
public sealed interface ArtilleryAction extends GameAction permits ArtilleryAction.Fire, ArtilleryAction.Skip {

    int MAX_MOVE = 100;

    /**
     * @param moveDx −100~100 (0 = 안 움직임)
     * @param angle  0~180 (0 = 오른쪽, 90 = 위)
     * @param power  0~100
     * @param shell  null 이면 콩알
     */
    record Fire(int moveDx, int angle, int power, Shell shell) implements ArtilleryAction {
        public Fire {
            if (shell == null) {
                shell = Shell.PEA;
            }
        }
    }

    record Skip() implements ArtilleryAction {
    }
}
