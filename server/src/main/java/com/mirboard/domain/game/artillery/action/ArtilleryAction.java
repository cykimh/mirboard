package com.mirboard.domain.game.artillery.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.core.GameAction;

/**
 * 씨앗포 액션 (`docs/rules-artillery.md` §4). 차례 주인은 {@link Move} 를 여러 번 보낸 뒤 {@link Fire}·{@link Skip} 하나로 차례를
 * 끝낸다(D-228 — 걸으면 바로 확정). {@link Fire#moveDx} 도 그대로 유효하다("이 자리에서 더 걷고 쏜다" — 봇·옛 클라).
 * {@link Preview} 는 액션이 아니라 조준 미리보기 입력이다 — {@code /preview} 경로로만 오고 {@code apply} 는 거절한다(D-229).
 * 판별자는 {@code @action}(다른 게임과 같은 STOMP 모양).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ArtilleryAction.Fire.class, name = "FIRE"),
        @JsonSubTypes.Type(value = ArtilleryAction.Skip.class, name = "SKIP"),
        @JsonSubTypes.Type(value = ArtilleryAction.Pick.class, name = "PICK"),
        @JsonSubTypes.Type(value = ArtilleryAction.Move.class, name = "MOVE"),
        @JsonSubTypes.Type(value = ArtilleryAction.Preview.class, name = "PREVIEW")
})
public sealed interface ArtilleryAction extends GameAction
        permits ArtilleryAction.Fire, ArtilleryAction.Skip, ArtilleryAction.Pick, ArtilleryAction.Move,
                ArtilleryAction.Preview {

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

    /**
     * 걷기 확정 (D-228). 부호 방향으로 {@code |dx|} 걸음 — 실제 걸음은 남은 연료·벽이 정한다(§5.1).
     *
     * @param dx −130~130, 0 아님
     */
    record Move(int dx) implements ArtilleryAction {
    }

    /**
     * 조준 미리보기 입력 (D-229) — 상태를 바꾸지 않는다. <b>힘은 필드가 없다</b>(숨김 — 실어 보내도 버린다).
     *
     * @param angle 포신 각도 0~180
     * @param shell 고른 탄(쏠 수 없는 탄이면 서버가 null 로 바꾼다), null 이면 안 알림
     * @param n     그 차례 안에서 보내는 쪽이 1씩 올리는 번호 — 받는 쪽이 낡은 프레임을 버리는 기준
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Preview(int angle, Shell shell, int n) implements ArtilleryAction {
    }
}
