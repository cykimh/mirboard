package com.mirboard.domain.game.omok.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 계산 예산 (D-179) — 봇은 방 액션 락(2초 TTL) 안에서 계산한다. 노드 상한이 지켜지는지(결정적) 단언하고, 걸린 시간은 측정해
 * 출력한다(기계마다 달라 단언은 넉넉히 — 설계 기준 p99 150ms 는 로컬 측정값으로 문서에 남긴다).
 */
class OmokBotBudgetTest {

    private static final OmokEngine ENGINE = new OmokEngine();

    /** 보통 봇 대 무작위로 둔 판에서 수마다 국면을 모은다. */
    private static List<OmokState> positions(int count, long seed) {
        Random rng = new Random(seed);
        List<OmokState> out = new ArrayList<>();
        while (out.size() < count) {
            OmokState s = ENGINE.startMatch(OmokMode.SOLO, OmokRule.RENJU, 2, 0, 0);
            int ply = 0;
            while (!s.isOver() && out.size() < count) {
                int seat = s.turnSeat();
                List<OmokAction> legal = ENGINE.legalActions(s, seat);
                OmokAction a = ply % 2 == 0 || rng.nextInt(3) == 0
                        ? legal.get(rng.nextInt(legal.size()))
                        : OmokBotPolicy.choose(OmokBotView.of(s), legal, BotLevel.NORMAL);
                s = ENGINE.apply(s, seat, a).newState();
                ply++;
                if (!s.isOver() && ply >= 4) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @Test
    void hard_stays_within_the_node_budget_and_reports_its_time() {
        List<OmokState> positions = positions(1_000, 77);
        long[] nanos = new long[positions.size()];
        int maxNodes = 0;
        for (int i = 0; i < positions.size(); i++) {
            OmokState s = positions.get(i);
            List<OmokAction> legal = ENGINE.legalActions(s, s.turnSeat());
            long t0 = System.nanoTime();
            OmokBotPolicy.Decision d = OmokBotPolicy.decide(OmokBotView.of(s), legal, BotLevel.HARD);
            nanos[i] = System.nanoTime() - t0;
            assertThat(legal).contains(d.action());
            maxNodes = Math.max(maxNodes, d.nodes());
        }
        Arrays.sort(nanos);
        double p50 = nanos[nanos.length / 2] / 1e6;
        double p99 = nanos[(int) (nanos.length * 0.99)] / 1e6;
        double max = nanos[nanos.length - 1] / 1e6;
        System.out.printf("omok HARD bot: p50 %.1fms p99 %.1fms max %.1fms, max nodes %d%n", p50, p99, max, maxNodes);
        // 노드 상한을 넘은 뒤에도 진행 중인 한 단계(후보 한 바퀴)만큼은 더 쓸 수 있다.
        assertThat(maxNodes).isLessThanOrEqualTo(OmokBotPolicy.NODE_BUDGET + 1_000);
        assertThat(max).isLessThan(1_500.0);                          // 락 TTL 2초 안 — 느린 CI 를 감안한 넉넉한 선
    }

}
