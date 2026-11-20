package com.mirboard.domain.game.dambaduk.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;

/**
 * 담바둑 액션 sealed 계층 (룰 §3·§4).
 *
 * <p><b>{@link TimeoutAct} 는 Jackson 하위 타입에 등록하지 않는다</b> — 클라 JSON 으로는 만들 수 없고 서버의 {@code timeoutAction} 만
 * 만든다(룰 §8). 엔진은 이것으로 사람별 연속 시간 초과를 세고 자동 수를 고른다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DambadukAction.Place.class, name = "PLACE_STONE"),
        @JsonSubTypes.Type(value = DambadukAction.Move.class, name = "MOVE_STONE")
})
public sealed interface DambadukAction extends GameAction
        permits DambadukAction.Place, DambadukAction.Move, DambadukAction.TimeoutAct {

    /** 배치 (룰 §3) — 2인은 {@code STRAIGHT} 만. */
    record Place(StoneKind kind, int x, int y) implements DambadukAction {
    }

    /**
     * 옮기고 담 세우기 (룰 §4) — 떼어 보낼 수 없는 한 액션.
     *
     * @param path 지나는 방향 목록(1~2개)
     * @param wall 도착 칸 기준 담 면 방향
     * @param jump 담 넘기(3인으로 시작한 판, 사람마다 1번) — JSON 에 없으면 false
     */
    record Move(int stoneId, List<Dir> path, Dir wall, boolean jump) implements DambadukAction {

        public Move {
            path = path == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(path));
        }

        public Move(int stoneId, List<Dir> path, Dir wall) {
            this(stoneId, path, wall, false);
        }
    }

    /** 시간 초과 (룰 §8) — 서버 내부 전용. 자동 수는 엔진이 정한다. */
    record TimeoutAct() implements DambadukAction {
    }
}
