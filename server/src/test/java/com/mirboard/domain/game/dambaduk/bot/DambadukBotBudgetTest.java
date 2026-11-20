package com.mirboard.domain.game.dambaduk.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Phase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 계산 예산 (D-277) — 봇은 방 액션 락(2초 TTL) 안에서 계산한다. 평가 상한이 지켜지는지(결정적) 단언하고, 걸린 시간은 측정해 출력한다
 * (기계마다 달라 단언은 넉넉히 — 설계 기준 p99 150ms 는 로컬 측정값으로 문서에 남긴다).
 */
class DambadukBotBudgetTest {

    private static final DambadukEngine ENGINE = new DambadukEngine();

    /** 보통 봇과 무작위 수를 섞어 둔 판에서 옮김 단계 국면을 모은다. */
    private static List<DambadukState> positions(int seats, int count, long seed) {
        Random rng = new Random(seed);
        List<DambadukState> out = new ArrayList<>();
        while (out.size() < count) {
            DambadukState s = ENGINE.startMatch(seats, rng.nextInt(seats));
            while (!s.isOver() && out.size() < count) {
                int seat = s.turnSeat();
                List<DambadukAction> legal = ENGINE.legalActions(s, seat);
                DambadukAction a = rng.nextInt(3) == 0
                        ? legal.get(rng.nextInt(legal.size()))
                        : DambadukBotPolicy.choose(DambadukBotView.of(s, seat), legal, DambadukBotLevel.NORMAL);
                s = ENGINE.apply(s, seat, a).newState();
                if (!s.isOver() && s.phase() == Phase.PLAYING) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {2, 3})
    void hard_stays_within_the_node_budget_and_reports_its_time(int seats) {
        List<DambadukState> positions = positions(seats, 400, 77L + seats);
        long[] nanos = new long[positions.size()];
        int maxNodes = 0;
        for (int i = 0; i < positions.size(); i++) {
            DambadukState s = positions.get(i);
            List<DambadukAction> legal = ENGINE.legalActions(s, s.turnSeat());
            long t0 = System.nanoTime();
            DambadukBotPolicy.Decision d = DambadukBotPolicy.decide(DambadukBotView.of(s, s.turnSeat()), legal,
                    DambadukBotLevel.HARD);
            nanos[i] = System.nanoTime() - t0;
            assertThat(legal).contains(d.action());
            maxNodes = Math.max(maxNodes, d.nodes());
        }
        Arrays.sort(nanos);
        double p50 = nanos[nanos.length / 2] / 1e6;
        double p99 = nanos[(int) (nanos.length * 0.99)] / 1e6;
        double max = nanos[nanos.length - 1] / 1e6;
        System.out.printf("dambaduk HARD bot %dp: p50 %.1fms p99 %.1fms max %.1fms, max nodes %d%n", seats, p50, p99,
                max, maxNodes);
        // 상한을 넘은 뒤에도 진행 중인 후보 하나의 응수 한 바퀴(≤ 8 도착 × 4 돌 × 4 면 × 2)만큼은 더 쓸 수 있다.
        assertThat(maxNodes).isLessThanOrEqualTo(DambadukBotPolicy.NODE_BUDGET + 600);
        assertThat(max).isLessThan(1_500.0);                          // 락 TTL 2초 안 — 느린 CI 를 감안한 넉넉한 선
    }
}
