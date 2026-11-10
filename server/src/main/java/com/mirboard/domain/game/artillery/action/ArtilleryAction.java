package com.mirboard.domain.game.artillery.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.core.GameAction;

/**
 * 씨앗포 액션 (`docs/rules-artillery.md` §4). 차례당 하나 — 이동은 {@link Fire#moveDx} 로 발사에 싣는다(D-220).
 * 판별자는 {@code @action}(다른 게임과 같은 STOMP 모양).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ArtilleryAction.Fire.class, name = "FIRE"),
        @JsonSubTypes.Type(value = ArtilleryAction.Skip.class, name = "SKIP"),
        @JsonSubTypes.Type(value = ArtilleryAction.Pick.class, name = "PICK")
})
public sealed interface ArtilleryAction extends GameAction
        permits ArtilleryAction.Fire, ArtilleryAction.Skip, ArtilleryAction.Pick {

    /** 이동 범위 상한 — 가장 큰 연료(민들레포 130, D-224). 실제로 걷는 거리는 연료가 정한다. */
    int MAX_MOVE = UnitKind.maxFuel();

    /**
     * @param moveDx −130~130 (0 = 안 움직임)
     * @param angle  0~180 (0 = 오른쪽, 90 = 위)
     * @param power  0~100
     * @param shell  null 이면 콩알
     * @param item   쓸 아이템, null 이면 없음 (D-224)
     */
    record Fire(int moveDx, int angle, int power, Shell shell, Item item) implements ArtilleryAction {
        public Fire {
            if (shell == null) {
                shell = Shell.PEA;
            }
        }

        public Fire(int moveDx, int angle, int power, Shell shell) {
            this(moveDx, angle, power, shell, null);
        }
    }

    record Skip() implements ArtilleryAction {
    }

    /** 판 시작 유닛 고르기 (D-224). */
    record Pick(UnitKind unit) implements ArtilleryAction {
    }
}
