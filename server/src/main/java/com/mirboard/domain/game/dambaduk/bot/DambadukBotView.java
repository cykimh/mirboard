package com.mirboard.domain.game.dambaduk.bot;

import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Stone;
import java.util.List;

/**
 * 봇이 보는 것 (D-118·D-119 원칙 — 공개 정보만). 담바둑은 상태 전부가 공개라 판(돌·담)·담 넘기 남음·빠지지 않은 좌석·내 좌석이면
 * 된다. 다음 차례 순서는 좌석 +1 이다.
 */
public record DambadukBotView(int seatCount, int me, Phase phase, List<Stone> stones, List<Integer> walls,
                              List<Integer> jumpsLeft, List<Integer> activeSeats, int moveCount) {

    public DambadukBotView {
        stones = List.copyOf(stones);
        walls = List.copyOf(walls);
        jumpsLeft = List.copyOf(jumpsLeft);
        activeSeats = List.copyOf(activeSeats);
    }

    public static DambadukBotView of(DambadukState state, int seat) {
        return new DambadukBotView(state.seatCount(), seat, state.phase(), state.stones(), state.walls(),
                state.jumpsLeft(), state.activeSeats(), state.moveCount());
    }

    /** 판 해시 — 동점 수 중 하나를 무작위 없이 결정적으로 고르는 데 쓴다. */
    public int boardHash() {
        int h = 17 * 31 + me;
        for (Stone s : stones) {
            h = h * 31 + s.id() * 64 + s.cell().index();
        }
        for (int w : walls) {
            h = h * 31 + w;
        }
        return h & 0x7fffffff;
    }
}
