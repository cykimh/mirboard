package com.mirboard.domain.game.dambaduk.invariant;

import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.rules.MoveRules;
import com.mirboard.domain.game.dambaduk.rules.Setup;
import com.mirboard.domain.game.dambaduk.rules.TerritoryRule;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.Exit;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.MatchResult;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 담바둑 불변식 (룰 §15). 시뮬레이션이 매 전이 뒤 부른다 — 위반이면 {@link IllegalStateException}.
 *
 * <p>수순을 저장하지 않으므로 개수의 짝으로 본다: 좌석별 돌 수 = 정해진 수 − 남은 배치 · 한 칸에 돌 하나 · 담 수 = 넘기 없는 옮김 수 ·
 * 담 넘기 사용 = 쓴 사람 수 · 끝나지 않은 판에는 다툼 터가 있고 굳지 않았으며 차례인 사람은 고를 수가 있음 · 모든 도착에 세울 면이 있음(§4.3) ·
 * 끝난 판의 사유·순위가 판과 맞음.
 */
public final class DambadukInvariantChecker {

    private DambadukInvariantChecker() {
    }

    public static void check(DambadukState s) {
        int n = s.seatCount();
        require(n == 2 || n == 3, "seat count " + n);
        require(s.firstSeat() >= 0 && s.firstSeat() < n, "first seat " + s.firstSeat());
        require(s.jumpsLeft().size() == n && s.timeouts().size() == n, "per-seat list sizes");

        // 빠진 좌석
        Set<Integer> out = new HashSet<>();
        for (Exit e : s.exits()) {
            require(e.seat() >= 0 && e.seat() < n, "exit seat " + e.seat());
            require(out.add(e.seat()), "seat " + e.seat() + " exited twice");
        }
        require(n == 3 || out.size() <= 1, "two exits in a two-player match");
        require(out.size() < n, "everyone exited");
        if (n == 2 && !out.isEmpty()) {
            require(s.isOver(), "a two-player match goes on after an exit");
        }

        // 돌
        Set<Integer> ids = new HashSet<>();
        Set<Integer> cells = new HashSet<>();
        for (Stone st : s.stones()) {
            require(st.cell().onBoard(), "stone " + st.id() + " off board");
            require(st.id() >= 0 && st.id() < s.nextStoneId(), "stone id " + st.id() + " ≥ next " + s.nextStoneId());
            require(ids.add(st.id()), "duplicate stone id " + st.id());
            require(cells.add(st.cell().index()), "two stones on " + st.cell());
            require(st.seat() >= 0 && st.seat() < n, "stone seat " + st.seat());
            require(n == 2 || !out.contains(st.seat()), "a removed seat still has stone " + st.id());
        }
        for (int seat = 0; seat < n; seat++) {
            if (n == 3 && out.contains(seat)) {
                continue;
            }
            int queued = countQueued(s, seat);
            int total = 0;
            for (StoneKind kind : StoneKind.values()) {
                int have = count(s.stonesOf(seat), kind);
                require(have <= Setup.quota(n, kind), "seat " + seat + " has " + have + " " + kind);
                total += have;
            }
            require(total == Setup.stonesPerSeat(n) - queued,
                    "seat " + seat + " has " + total + " stones with " + queued + " placements left");
            if (n == 2) {
                require(count(s.stonesOf(seat), StoneKind.BENT) == 2, "two-player bent stones are fixed");
            }
        }
        for (int q : s.placementQueue()) {                       // 2인은 빠지면 그 자리에서 끝나 판을 그대로 둔다
            require(n == 2 || !out.contains(q), "placement queue holds removed seat " + q);
        }

        // 담·담 넘기
        Walls walls = s.wallSet();
        require(walls.count() == s.walls().size(), "duplicate walls " + s.walls());
        for (int f : s.walls()) {
            require(f >= 0 && f < Grid.FACES, "wall face " + f);
        }
        require(s.walls().size() == s.moveCount() - s.jumpCount(),
                "walls " + s.walls().size() + " ≠ moves " + s.moveCount() + " − jumps " + s.jumpCount());
        require(s.moveCount() <= Grid.FACES + n, "move count " + s.moveCount() + " over the bound");
        int used = 0;
        for (int seat = 0; seat < n; seat++) {
            int j = s.jumpsLeft().get(seat);
            require(j == 0 || j == 1, "jumps left " + j);
            if (!s.jumpsEnabled()) {
                require(j == 0, "a two-player match has a jump");
            } else {
                used += 1 - j;
            }
        }
        if (s.jumpsEnabled()) {
            require(used == s.jumpCount(), "jumps used " + used + " ≠ jump count " + s.jumpCount());
        } else {
            require(s.jumpCount() == 0, "jump count in a two-player match");
        }

        // §4.3 — 모든 도착에 세울 면이 있다
        for (int seat = 0; seat < n; seat++) {
            if (!s.isActive(seat)) {
                continue;
            }
            for (MoveOption o : MoveRules.options(s.stones(), seat, walls, s.jumpsLeft().get(seat) > 0, true)) {
                require(!o.walls().isEmpty(), "a destination without a free side: stone " + o.stoneId() + " → ("
                        + o.toX() + "," + o.toY() + ")");
            }
        }

        Territory terr = TerritoryRule.compute(walls, s.stones(), n);
        MatchResult r = s.result();
        if (r == null) {
            require(s.phase() != Phase.ENDED, "ENDED without a result");
            require(s.isActive(s.turnSeat()), "turn seat " + s.turnSeat() + " is not active");
            for (int seat = 0; seat < n; seat++) {
                int t = s.timeouts().get(seat);
                require(!s.isActive(seat) || (t >= 0 && t <= 1), "timeout count " + t + " for seat " + seat);
            }
            if (s.phase() == Phase.PLACING) {
                require(!s.placementQueue().isEmpty(), "placing with an empty queue");
                require(s.turnSeat() == s.placementQueue().getFirst(), "placing turn ≠ queue head");
                require(s.moveCount() == 0 && s.walls().isEmpty(), "walls while placing");
            } else {
                require(s.placementQueue().isEmpty(), "playing with placements left");
                require(terr.hasContested(), "playing but nothing is contested");
                require(!DambadukEngine.isFrozen(s.stones(), walls, s.jumpsLeft(), terr, new HashSet<>(s.activeSeats())),
                        "playing but frozen");
                require(!MoveRules.options(s.stones(), s.turnSeat(), walls, s.jumpsLeft().get(s.turnSeat()) > 0, false)
                        .isEmpty(), "turn seat " + s.turnSeat() + " cannot move");
            }
            return;
        }

        require(s.phase() == Phase.ENDED && s.turnSeat() == -1, "ended match phase/turn");
        List<Standing> expected = DambadukEngine.standings(terr, n, s.exits());
        require(r.standings().equals(expected), "standings " + r.standings() + " ≠ " + expected);
        boolean draw = s.exits().isEmpty() && expected.stream().allMatch(x -> x.rank() == 1);
        require(r.draw() == draw, "draw flag " + r.draw());
        List<Integer> winners = draw ? List.of()
                : expected.stream().filter(x -> x.exit() == null && x.rank() == 1).map(Standing::seat).toList();
        require(r.winners().equals(winners), "winners " + r.winners() + " ≠ " + winners);
        int activeCount = n - out.size();
        switch (r.reason()) {
            case SEPARATED -> {
                require(!terr.hasContested(), "SEPARATED but contested");
                require(r.causeSeat() == -1, "cause seat on SEPARATED");
            }
            case FROZEN -> {
                require(DambadukEngine.isFrozen(s.stones(), walls, s.jumpsLeft(), terr, new HashSet<>(s.activeSeats())),
                        "FROZEN but something moves");
                require(r.causeSeat() == -1, "cause seat on FROZEN");
            }
            case LAST_STANDING -> {
                require(n == 3 && activeCount == 1, "LAST_STANDING with " + activeCount + " left of " + n);
            }
            case TIMEOUT, DESERTED -> {
                require(n == 2, r.reason() + " end in a three-player match");
                ExitReason why = r.reason() == EndReason.TIMEOUT ? ExitReason.TIMEOUT : ExitReason.DESERTED;
                require(s.exits().size() == 1 && s.exits().getFirst().equals(new Exit(r.causeSeat(), why)),
                        "cause " + r.causeSeat() + " vs exits " + s.exits());
            }
        }
    }

    private static int countQueued(DambadukState s, int seat) {
        return (int) s.placementQueue().stream().filter(q -> q == seat).count();
    }

    private static int count(List<Stone> stones, StoneKind kind) {
        return (int) stones.stream().filter(st -> st.kind() == kind).count();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("Dambaduk invariant violated: " + message);
        }
    }
}
