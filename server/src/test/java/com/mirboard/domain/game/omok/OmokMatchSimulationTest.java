package com.mirboard.domain.game.omok;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.action.OmokActionRejectedException;
import com.mirboard.domain.game.omok.action.RejectionReason;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.invariant.OmokInvariantChecker;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 무작위 판 시뮬레이션 (룰 §13) — 모드 2 × 룰 3. 매 전이 뒤 불변식을 보고, 공개 이벤트만 듣는 클라 모형이 그린 판·차례가
 * 엔진 상태와 같은지 대조한다. 착수는 무작위 빈칸(흑 금수면 다른 칸), 가끔 시간 초과·기권·탈주를 섞는다. 시드 고정.
 */
class OmokMatchSimulationTest {

    private static final OmokEngine ENGINE = new OmokEngine();
    private static final int GAMES = 3_000;

    static List<Arguments> combos() {
        return List.of(OmokMode.values()).stream()
                .flatMap(m -> List.of(OmokRule.values()).stream().map(r -> Arguments.of(m, r)))
                .toList();
    }

    /** 공개 이벤트만으로 그린 판. */
    private static final class ClientModel {
        Board board = Board.empty();
        int turnSeat;
        boolean over;

        ClientModel(int firstSeat) {
            turnSeat = firstSeat;
        }

        void apply(List<OmokEvent> events) {
            for (OmokEvent e : events) {
                switch (e) {
                    case OmokEvent.StonePlaced sp -> {
                        Point p = new Point(sp.x(), sp.y());
                        if (board.isEmpty(p)) {            // 같은 수를 두 번 받아도 같은 판
                            board = board.with(p, sp.color());
                        }
                    }
                    case OmokEvent.TurnChanged tc -> turnSeat = tc.seat();
                    case OmokEvent.MatchEnded __ -> {
                        over = true;
                        turnSeat = -1;
                    }
                    case OmokEvent.MatchStarted __ -> { }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("combos")
    void random_matches_always_end_and_keep_the_invariants(OmokMode mode, OmokRule rule) {
        Random rng = new Random(mode.ordinal() * 31L + rule.ordinal() * 7L + 2026L);
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        for (int game = 0; game < GAMES; game++) {
            int blackTeam = rng.nextInt(2);
            List<Integer> blackSeats = mode == OmokMode.SOLO ? List.of(blackTeam) : List.of(blackTeam, blackTeam + 2);
            int firstSeat = blackSeats.get(rng.nextInt(blackSeats.size()));
            OmokState s = ENGINE.startMatch(mode, rule, mode.seatCount(), blackTeam, firstSeat);
            ClientModel client = new ClientModel(firstSeat);
            boolean fullCheck = game < 30;                              // 처음 30판은 매 전이, 나머지는 끝에서만 (수순 재생이 O(n²))
            int steps = 0;
            while (!s.isOver()) {
                OmokEngine.Result r = step(s, rng);
                s = r.newState();
                client.apply(r.events());
                if (fullCheck) {
                    OmokInvariantChecker.check(s);
                }
                assertThat(client.board).isEqualTo(s.board());
                assertThat(client.turnSeat).isEqualTo(s.turnSeat());
                assertThat(++steps).isLessThanOrEqualTo(Board.CELLS + 1);
            }
            OmokInvariantChecker.check(s);
            assertThat(client.over).isTrue();
            reasons.merge(s.result().reason(), 1, Integer::sum);
        }
        assertThat(reasons.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(GAMES);
        assertThat(reasons).containsKey(EndReason.FIVE);
        System.out.printf("omok sim %s %s: %s%n", mode, rule, reasons);
    }

    private static OmokEngine.Result step(OmokState s, Random rng) {
        int seat = s.turnSeat();
        double roll = rng.nextDouble();
        if (roll < 0.0005) {
            return ENGINE.apply(s, rng.nextInt(s.seatCount()), new OmokAction.Resign());
        }
        if (roll < 0.0008) {
            OmokEngine.Desertion d = ENGINE.desert(s, rng.nextInt(s.seatCount()));
            return new OmokEngine.Result(d.newState(), d.events());
        }
        if (roll < 0.015) {
            return ENGINE.apply(s, seat, ENGINE.timeoutAction(s, seat));
        }
        Board board = s.board();
        for (int attempt = 0; attempt < 400; attempt++) {
            Point p = new Point(rng.nextInt(Board.SIZE), rng.nextInt(Board.SIZE));
            if (!board.isEmpty(p)) {
                continue;
            }
            try {
                return ENGINE.apply(s, seat, new OmokAction.Place(p.x(), p.y()));
            } catch (OmokActionRejectedException e) {
                assertThat(e.reason()).isEqualTo(RejectionReason.FORBIDDEN_MOVE);
                assertThat(s.colorOf(seat)).isEqualTo(Stone.BLACK);
            }
        }
        List<Point> legal = ENGINE.legalPoints(s);                     // 판이 거의 찬 경우
        Point p = legal.get(rng.nextInt(legal.size()));
        return ENGINE.apply(s, seat, new OmokAction.Place(p.x(), p.y()));
    }
}
