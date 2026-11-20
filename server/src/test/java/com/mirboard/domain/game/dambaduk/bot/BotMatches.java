package com.mirboard.domain.game.dambaduk.bot;

import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Standing;
import java.util.List;
import java.util.Random;

/**
 * 봇끼리 두는 판 (D-277 강도 평가). {@code null} 단계는 무작위 합법 수. 결정적 봇끼리는 같은 판이 되풀이되므로 첫 두 수(배치)를
 * 시드 무작위로 흔든다.
 */
final class BotMatches {

    static final DambadukEngine ENGINE = new DambadukEngine();

    private BotMatches() {
    }

    /** 판 하나 — 좌석별 단계, 선 좌석, 시드. 끝난 상태를 돌려준다. */
    static DambadukState play(DambadukBotLevel[] levels, int firstSeat, long seed) {
        Random rng = new Random(seed);
        DambadukState s = ENGINE.startMatch(levels.length, firstSeat);
        int ply = 0;
        while (!s.isOver()) {
            int seat = s.turnSeat();
            List<DambadukAction> legal = ENGINE.legalActions(s, seat);
            DambadukBotLevel level = levels[seat];
            DambadukAction a = level == null || ply < 2
                    ? legal.get(rng.nextInt(legal.size()))
                    : DambadukBotPolicy.choose(DambadukBotView.of(s, seat), legal, level);
            s = ENGINE.apply(s, seat, a).newState();
            ply++;
        }
        return s;
    }

    /** 좌석 0 의 점수 — 1위 단독 1, 공동 1위 0.5(2인 무승부 포함), 나머지 0. */
    static double scoreOf(DambadukState s, int seat) {
        List<Standing> st = s.result().standings();
        long firsts = st.stream().filter(x -> x.rank() == 1).count();
        return st.get(seat).rank() == 1 ? 1.0 / firsts : 0.0;
    }

    /** 2인 — a 가 b 를 상대로 낸 점수 평균. 선을 번갈아. */
    static double winRate2(DambadukBotLevel a, DambadukBotLevel b, int games, long seed) {
        double total = 0;
        for (int g = 0; g < games; g++) {
            DambadukState s = play(new DambadukBotLevel[] {a, b}, g % 2, seed + g);
            total += scoreOf(s, 0);
        }
        return total / games;
    }

    /** 3인 — a 하나 대 b 둘, a 의 좌석·선을 돌려가며. 기준선은 1/3. */
    static double winRate3(DambadukBotLevel a, DambadukBotLevel b, int games, long seed) {
        double total = 0;
        for (int g = 0; g < games; g++) {
            int aSeat = g % 3;
            DambadukBotLevel[] levels = {b, b, b};
            levels[aSeat] = a;
            DambadukState s = play(levels, (g / 3) % 3, seed + g);
            total += scoreOf(s, aSeat);
        }
        return total / games;
    }
}
