package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 봇 좌석의 지금 퍼즐 페이스(`docs/rules-starsquare.md` §10). 퍼즐이 시작될 때 정해 상태에 둔다 — 수를 고르지 않고 이 계획대로
 * 정답 칸을 채운다.
 *
 * @param solveAfterMs 퍼즐 시작부터 완성까지(ms)
 * @param order        채울 빈 칸 번호의 순서(단서 아닌 칸 전부)
 * @param milestones   이정표 수(최대 6, 빈 칸 수 이하)
 * @param reached      지나간 이정표 수(0 ~ milestones)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotPlan(long solveAfterMs, List<Integer> order, int milestones, int reached) {

    public static final int MAX_MILESTONES = 6;

    public BotPlan {
        order = List.copyOf(order);
    }

    /** j번째 이정표(1 ~ milestones)의 퍼즐 시작 기준 시각(ms). 마지막이 완성이다. */
    public long milestoneAt(int j) {
        return solveAfterMs * j / milestones;
    }

    /** j번째 이정표까지 채운 칸 수 — ⌈빈 칸 수 × j / 이정표 수⌉. */
    public int cellsThrough(int j) {
        return (order.size() * j + milestones - 1) / milestones;
    }

    public boolean done() {
        return reached >= milestones;
    }

    public BotPlan advanced() {
        return new BotPlan(solveAfterMs, order, milestones, reached + 1);
    }
}
