package com.mirboard.domain.game.cloudroute.bot;

import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Pending;
import java.util.List;

/**
 * 봇이 보는 정보 (D-212) — 공개 정보만. 구름항로에서 숨기는 것은 편지 더미 순서뿐이라(룰 §13) 그것만 빼고 담는다. 봇이 다음 편지를
 * 알면 사람에게 없는 정보로 수를 고르게 된다.
 *
 * @param me     봇 좌석
 * @param cash   좌석별 현금(탈락자 0)
 * @param alive  좌석별 생존
 * @param owners 칸별 주인(없으면 −1)
 * @param levels 칸별 단계
 */
public record CloudRouteBotView(int me,
                                Pending pending,
                                List<Integer> cash,
                                List<Boolean> alive,
                                List<Integer> owners,
                                List<Integer> levels,
                                int festivalTile,
                                int buoyHolder,
                                int round,
                                int roundLimit) {

    public static CloudRouteBotView of(CloudRouteState state, int seat) {
        List<Integer> cash = state.seats().stream().map(s -> s.cash()).toList();
        List<Boolean> alive = java.util.stream.IntStream.range(0, state.seatCount()).mapToObj(state::alive).toList();
        return new CloudRouteBotView(seat, state.pending(), cash, alive, state.owners(), state.levels(),
                state.festivalTile(), state.buoyHolder(), state.round(), state.roundLimit());
    }

    public int myCash() {
        return cash.get(me);
    }
}
