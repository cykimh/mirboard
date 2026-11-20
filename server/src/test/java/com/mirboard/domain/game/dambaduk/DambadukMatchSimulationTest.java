package com.mirboard.domain.game.dambaduk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.action.DambadukActionRejectedException;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.invariant.DambadukInvariantChecker;
import com.mirboard.domain.game.dambaduk.rules.TerritoryRule;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 무작위 판 시뮬레이션 (룰 §15) — 2인·3인 각 {@value #GAMES}판, 시드 고정.
 *
 * <ul>
 *   <li>매 전이 뒤 불변식, 공개 이벤트만 듣는 클라 모형의 돌·담·차례·단계가 엔진과 같은지, 마지막 {@code TURN_CHANGED} 의 터·고를 수 있는
 *       수가 엔진 계산과 같은지 대조한다.</li>
 *   <li>매 단계 아무렇게나 만든 액션 하나를 던져 "받아들여짐 ⇔ 합법 수 목록에 있음"을 본다(거절은 늘 {@link DambadukActionRejectedException}).</li>
 *   <li>수는 무작위 합법 수, 가끔 시간 초과·탈주를 섞는다. 전부 끝나야 하고 옮김 수 ≤ 84 + 인원.</li>
 * </ul>
 * 무작위 수는 실제 판 길이와 거리가 멀어, 1수 탐욕 정책(내 집 − 남의 가장 큰 점수)으로 {@value #GREEDY_GAMES}판을 더 둬 길이·끝 사유
 * 분포를 어림한다(S2 봇 정책이 아니다). 통계는 {@code server/build/dambaduk-sim-stats.txt}.
 */
class DambadukMatchSimulationTest {

    private static final DambadukEngine ENGINE = new DambadukEngine();
    private static final int GAMES = 2_000;
    private static final int GREEDY_GAMES = 100;
    private static final Map<String, String> REPORT = new TreeMap<>();

    /** 공개 이벤트만으로 그린 판. */
    private static final class ClientModel {
        final Map<Integer, Stone> stones = new HashMap<>();
        final Set<Integer> walls = new HashSet<>();
        int turn;
        Phase phase;
        Territory territory;
        List<MoveOption> options = List.of();
        int skips;

        ClientModel(DambadukState start) {                          // 시작 상태는 resync(공개 뷰)로 받는다
            start.stones().forEach(s -> stones.put(s.id(), s));
            walls.addAll(start.walls());
            turn = start.turnSeat();
            phase = start.phase();
        }

        void apply(List<DambadukEvent> events) {
            for (DambadukEvent e : events) {
                switch (e) {
                    case DambadukEvent.StonePlaced p -> stones.put(p.stoneId(),
                            new Stone(p.stoneId(), p.seat(), p.kind(), p.x(), p.y()));
                    case DambadukEvent.StoneMoved m -> {
                        Stone old = stones.get(m.stoneId());
                        assertThat(old.x()).isEqualTo(m.fromX());
                        assertThat(old.y()).isEqualTo(m.fromY());
                        stones.put(m.stoneId(), new Stone(m.stoneId(), m.seat(), old.kind(), m.toX(), m.toY()));
                        if (m.jumpedFace() >= 0) {
                            assertThat(walls.remove(m.jumpedFace())).isTrue();
                        }
                        assertThat(walls.add(m.wallFace())).isTrue();
                        assertThat(m.wallFace()).isEqualTo(Grid.face(new com.mirboard.domain.game.dambaduk.board.Cell(
                                m.toX(), m.toY()), m.wall()));
                    }
                    case DambadukEvent.TurnChanged tc -> {
                        turn = tc.seat();
                        phase = tc.phase();
                        territory = tc.territory();
                        options = tc.options();
                    }
                    case DambadukEvent.TurnSkipped __ -> skips++;
                    case DambadukEvent.SeatOut so -> so.removedStoneIds().forEach(stones::remove);
                    case DambadukEvent.MatchEnded me -> {
                        turn = -1;
                        phase = Phase.ENDED;
                        territory = me.territory();
                    }
                }
            }
        }

        void assertMatches(DambadukState s, boolean sawTurnOrEnd) {
            assertThat(new ArrayList<>(stones.values()).stream().sorted(java.util.Comparator.comparingInt(Stone::id))
                    .toList()).isEqualTo(s.stones());
            assertThat(walls).isEqualTo(new HashSet<>(s.walls()));
            assertThat(turn).isEqualTo(s.turnSeat());
            assertThat(phase).isEqualTo(s.phase());
            if (sawTurnOrEnd) {
                assertThat(territory).isEqualTo(ENGINE.territory(s));
                if (!s.isOver() && s.phase() == Phase.PLAYING) {
                    assertThat(options).isEqualTo(ENGINE.options(s, s.turnSeat()));
                }
            }
        }
    }

    private record Outcome(EndReason reason, int moves, int skips, int jumps, int exits, boolean draw) {
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {2, 3})
    void random_matches_always_end_and_keep_the_invariants(int seats) {
        Random rng = new Random(2026L * seats + 11);
        List<Outcome> outcomes = new ArrayList<>(GAMES);
        int probes = 0;
        int probesAccepted = 0;
        for (int game = 0; game < GAMES; game++) {
            DambadukState s = ENGINE.startMatch(seats, rng.nextInt(seats));
            DambadukInvariantChecker.check(s);
            ClientModel client = new ClientModel(s);
            int steps = 0;
            while (!s.isOver()) {
                if (rng.nextDouble() < 0.25) {                           // 아무렇게나 만든 액션 — 받아들여짐 ⇔ 합법
                    probes++;
                    if (probe(s, rng)) {
                        probesAccepted++;
                    }
                }
                List<DambadukEvent> events = step(s, rng);
                s = lastState;
                client.apply(events);
                DambadukInvariantChecker.check(s);
                boolean saw = events.stream().anyMatch(e -> e instanceof DambadukEvent.TurnChanged
                        || e instanceof DambadukEvent.MatchEnded);
                client.assertMatches(s, saw);
                assertThat(++steps).as("steps").isLessThanOrEqualTo(Grid.FACES + seats + 8 + seats);
            }
            assertThat(s.moveCount()).isLessThanOrEqualTo(Grid.FACES + seats);
            outcomes.add(new Outcome(s.result().reason(), s.moveCount(), client.skips, s.jumpCount(), s.exits().size(),
                    s.result().draw()));
        }
        assertThat(outcomes).hasSize(GAMES);
        assertThat(probesAccepted).isPositive().isLessThan(probes);
        assertThat(outcomes).anyMatch(o -> o.reason() == EndReason.SEPARATED);
        report(seats, "random", outcomes);
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {2, 3})
    void greedy_matches_estimate_real_match_length(int seats) {
        Random rng = new Random(77L * seats);
        List<Outcome> outcomes = new ArrayList<>(GREEDY_GAMES);
        for (int game = 0; game < GREEDY_GAMES; game++) {
            DambadukState s = ENGINE.startMatch(seats, rng.nextInt(seats));
            int skips = 0;
            while (!s.isOver()) {
                DambadukAction a = greedy(s, rng);
                DambadukEngine.Result r = ENGINE.apply(s, s.turnSeat(), a);
                skips += (int) r.events().stream().filter(e -> e instanceof DambadukEvent.TurnSkipped).count();
                s = r.newState();
            }
            DambadukInvariantChecker.check(s);
            outcomes.add(new Outcome(s.result().reason(), s.moveCount(), skips, s.jumpCount(), 0, s.result().draw()));
        }
        report(seats, "greedy", outcomes);
    }

    // ---------- 한 단계 ----------

    private DambadukState lastState;

    private List<DambadukEvent> step(DambadukState s, Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.0015) {
            List<Integer> active = s.activeSeats();
            DambadukEngine.Desertion d = ENGINE.desert(s, active.get(rng.nextInt(active.size())));
            assertThat(d.outcome()).isNotEqualTo(DambadukEngine.Desertion.Outcome.NOT_APPLICABLE);
            lastState = d.newState();
            return d.events();
        }
        int seat = s.turnSeat();
        DambadukAction a;
        if (roll < 0.02) {
            a = ENGINE.timeoutAction(s, seat);
        } else {
            List<DambadukAction> legal = ENGINE.legalActions(s, seat);
            assertThat(legal).isNotEmpty();
            a = legal.get(rng.nextInt(legal.size()));
        }
        DambadukEngine.Result r = ENGINE.apply(s, seat, a);
        lastState = r.newState();
        return r.events();
    }

    /** 아무렇게나 만든 액션을 던진다 — 받아들여지면 합법 수 목록에 있어야 하고, 거절이면 담바둑 거절 예외여야 한다. */
    private static boolean probe(DambadukState s, Random rng) {
        int seat = rng.nextInt(10) == 0 ? rng.nextInt(s.seatCount()) : s.turnSeat();
        DambadukAction a;
        if (rng.nextBoolean()) {
            StoneKind kind = rng.nextInt(8) == 0 ? null : StoneKind.values()[rng.nextInt(2)];
            a = new DambadukAction.Place(kind, rng.nextInt(9) - 1, rng.nextInt(9) - 1);
        } else {
            List<Dir> path = new ArrayList<>();
            int len = rng.nextInt(4);
            for (int i = 0; i < len; i++) {
                path.add(Dir.values()[rng.nextInt(4)]);
            }
            Dir wall = rng.nextInt(10) == 0 ? null : Dir.values()[rng.nextInt(4)];
            int stoneId = rng.nextInt(s.nextStoneId() + 2) - 1;
            a = new DambadukAction.Move(stoneId, path, wall, rng.nextInt(4) == 0);
        }
        boolean legal = ENGINE.legalActions(s, seat).contains(a);
        try {
            ENGINE.apply(s, seat, a);
            assertThat(legal).as("accepted but not in legal actions: %s", a).isTrue();
            return true;
        } catch (DambadukActionRejectedException e) {
            assertThat(legal).as("rejected (%s) but in legal actions: %s", e.reason(), a).isFalse();
            return false;
        }
    }

    // ---------- 1수 탐욕 (길이 어림용) ----------

    private static DambadukAction greedy(DambadukState s, Random rng) {
        int seat = s.turnSeat();
        List<DambadukAction> legal = ENGINE.legalActions(s, seat);
        if (s.phase() == Phase.PLACING) {
            return legal.get(rng.nextInt(legal.size()));
        }
        int best = Integer.MIN_VALUE;
        List<DambadukAction> bests = new ArrayList<>();
        for (DambadukAction a : legal) {
            DambadukState n = ENGINE.apply(s, seat, a).newState();
            Territory t = TerritoryRule.compute(n.wallSet(), n.stones(), n.seatCount());
            int mine = t.scores().get(seat);
            int theirs = 0;
            for (int o = 0; o < n.seatCount(); o++) {
                if (o != seat) {
                    theirs = Math.max(theirs, t.scores().get(o));
                }
            }
            int v = mine - theirs;
            if (v > best) {
                best = v;
                bests.clear();
            }
            if (v == best) {
                bests.add(a);
            }
        }
        return bests.get(rng.nextInt(bests.size()));
    }

    // ---------- 통계 ----------

    private static void report(int seats, String policy, List<Outcome> outcomes) {
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        outcomes.forEach(o -> reasons.merge(o.reason(), 1, Integer::sum));
        int[] moves = outcomes.stream().mapToInt(Outcome::moves).sorted().toArray();
        double avg = java.util.Arrays.stream(moves).average().orElse(0);
        long withSkips = outcomes.stream().filter(o -> o.skips() > 0).count();
        long jumps = outcomes.stream().mapToInt(Outcome::jumps).sum();
        long draws = outcomes.stream().filter(Outcome::draw).count();
        String line = "%d인 %s %d판: 끝 %s · 옮김 수 평균 %.1f / 중앙 %d / 95%% %d / 최대 %d · 건너뜀 있는 판 %d · 담 넘기 %d · 무승부 %d"
                .formatted(seats, policy, outcomes.size(), reasons, avg, moves[moves.length / 2],
                        moves[(int) (moves.length * 0.95)], moves[moves.length - 1], withSkips, jumps, draws);
        System.out.println("dambaduk sim " + line);
        REPORT.put(seats + "-" + policy, line);
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (REPORT.isEmpty()) {
            return;
        }
        Path out = Path.of("build", "dambaduk-sim-stats.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "담바둑 시뮬레이션(시드 고정 — random 은 무작위 합법 수 + 시간 초과·탈주 조금, greedy 는 1수 탐욕)\n"
                + String.join("\n", REPORT.values()) + "\n");
    }
}
