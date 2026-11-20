package com.mirboard.domain.game.starsquare.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mirboard.domain.game.core.GameAction;
import java.util.List;

/**
 * 별자리 수판 액션 sealed 계층(`docs/rules-starsquare.md` §7) — 클라가 보낼 수 있는 것은 {@link Sync} 하나다.
 *
 * <p>준비 끝·마감·봇 진행·결과 보기 끝은 여기 없다 — 클라 JSON 으로 역직렬화되는 계층에 두면 위조할 수 있으므로 엔진의
 * {@code onTimer} 가 맡는다(§9, 포트 ⑦).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StarSquareAction.Sync.class, name = "SYNC")
})
public sealed interface StarSquareAction extends GameAction permits StarSquareAction.Sync {

    /**
     * 내 판 전체(§7). 칸마다 보내지 않고 모아 보낸다 — 멱등이고 마지막 판이 이긴다.
     *
     * @param puzzle  지금 퍼즐 번호(0 부터) — 지난 퍼즐의 늦은 판을 거르는 기준
     * @param version 보낸 쪽이 퍼즐마다 올리는 번호 — 저장된 버전보다 커야 받는다(같고 판도 같으면 받되 무변화)
     * @param grid    판 전체(길이 N², 단서 칸 포함, 0 = 빈 칸). 검증은 엔진이 한다(길이·범위 → {@code BAD_GRID})
     */
    record Sync(int puzzle, long version, List<Integer> grid) implements StarSquareAction {

        public Sync {
            grid = grid == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(grid));
        }
    }
}
