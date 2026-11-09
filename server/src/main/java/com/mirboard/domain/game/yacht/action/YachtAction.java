package com.mirboard.domain.game.yacht.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.score.Category;

/**
 * 요트 액션 sealed 계층 — 클라가 보낼 수 있는 것 전부(`docs/rules-yacht.md` §4).
 *
 * <p>시간 초과 자동 처리는 여기 없다 — 클라 JSON 으로 역직렬화되는 계층에 두면 클라가 위조할 수 있으므로 엔진의
 * {@code expire} 가 따로 맡는다(§6, 포트 ⑦).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = YachtAction.Roll.class, name = "ROLL"),
        @JsonSubTypes.Type(value = YachtAction.Hold.class, name = "HOLD"),
        @JsonSubTypes.Type(value = YachtAction.Score.class, name = "SCORE")
})
public sealed interface YachtAction extends GameAction
        permits YachtAction.Roll, YachtAction.Hold, YachtAction.Score {

    /**
     * 굴리기 (§4-1·2). 홀드하지 않은 주사위만 굴린다.
     *
     * @param held 실으면 먼저 그 홀드를 적용한다(첫 굴림은 모두 false 여야 한다). null 이면 지금 홀드 그대로
     */
    record Roll(HoldMask held) implements YachtAction {

        public static Roll plain() {
            return new Roll(null);
        }
    }

    /** 홀드 바꾸기 (§4-4) — 굴림 1·2번째 뒤에만. */
    record Hold(HoldMask held) implements YachtAction {
    }

    /** 기록하기 (§4-5) — 빈 칸 하나, 1번 이상 굴린 뒤. */
    record Score(Category category) implements YachtAction {
    }
}
