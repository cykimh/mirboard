package com.mirboard.domain.game.omok.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;

/**
 * 오목 액션 sealed 계층 (설계서 §5).
 *
 * <p><b>{@link TimeoutPlace} 는 Jackson 하위 타입에 등록하지 않는다</b> — 클라 JSON 으로는 만들 수 없고 서버의
 * {@code timeoutAction} 만 만든다(룰 §8, D-178). 엔진은 이것으로 사람별 연속 시간 초과를 센다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OmokAction.Place.class, name = "PLACE_STONE"),
        @JsonSubTypes.Type(value = OmokAction.Resign.class, name = "RESIGN")
})
public sealed interface OmokAction extends GameAction
        permits OmokAction.Place, OmokAction.Resign, OmokAction.TimeoutPlace {

    /** 돌 하나 두기 (룰 §4). */
    record Place(int x, int y) implements OmokAction {
    }

    /** 기권 (룰 §7) — 차례와 무관. 2:2 는 팀 패. */
    record Resign() implements OmokAction {
    }

    /** 시간 초과 자동 수 (룰 §8) — 서버 내부 전용. 자리는 엔진이 정한다. */
    record TimeoutPlace() implements OmokAction {
    }
}
