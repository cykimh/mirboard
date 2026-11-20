package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.action.DambadukActionRejectedException;
import com.mirboard.domain.game.dambaduk.action.RejectionReason;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Partition;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.event.DambadukEvent;
import com.mirboard.domain.game.dambaduk.rules.MoveRules;
import com.mirboard.domain.game.dambaduk.rules.Paths;
import com.mirboard.domain.game.dambaduk.rules.Setup;
import com.mirboard.domain.game.dambaduk.rules.TerritoryRule;
import com.mirboard.domain.game.dambaduk.rules.Walk;
import com.mirboard.domain.game.dambaduk.state.DambadukPublicView;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import com.mirboard.domain.game.dambaduk.state.Exit;
import com.mirboard.domain.game.dambaduk.state.ExitReason;
import com.mirboard.domain.game.dambaduk.state.MatchResult;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.RegionKind;
import com.mirboard.domain.game.dambaduk.state.Standing;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import com.mirboard.domain.game.dambaduk.state.Territory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 담바둑 순수 룰 엔진 (룰 정본 {@code docs/rules-dambaduk.md}, D-274·D-275). 저장소·시계·난수가 없다 — 선 추첨은 라운드 스타터가
 * 하고 결과를 {@link #startMatch} 인자로 넘긴다. 같은 입력이면 같은 결과다.
 *
 * <p>전이는 상태 레코드의 가변 사본({@code Table})에서 만들고 마지막에 얼린다(구름항로 D-211 과 같은 모양). 이벤트는 전부 공개다.
 */
public final class DambadukEngine {

    /** 시간 초과 배치의 기준 칸 — 가운데 D4 (룰 §8). */
    public static final Cell CENTER = new Cell(3, 3);

    public record Result(DambadukState newState, List<DambadukEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** 탈주 결과 (룰 §9). */
    public record Desertion(DambadukState newState, Outcome outcome, List<DambadukEvent> events) {

        public enum Outcome {
            /** 이미 끝났거나 이미 빠진 좌석 — 상태 무변경, 이벤트 0건. */
            NOT_APPLICABLE,
            /** 3인 판 — 돌을 치우고 남은 사람끼리 계속. */
            CONTINUED,
            /** 2인 판이거나 1명만 남아 끝났다. */
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---------- 시작 (룰 §2·§3) ----------

    /**
     * 새 판. 선 좌석은 호출자가 추첨해 넘긴다. 2인은 꺾은돌 넷이 고정 칸에 놓인 채로 시작한다(돌 번호 0 흑 B2 · 1 흑 F6 · 2 백 F2 ·
     * 3 백 B6).
     */
    public DambadukState startMatch(int seatCount, int firstSeat) {
        if (seatCount != 2 && seatCount != 3) {
            throw new IllegalArgumentException("Dambaduk is 2~3 players: " + seatCount);
        }
        if (firstSeat < 0 || firstSeat >= seatCount) {
            throw new IllegalArgumentException("firstSeat " + firstSeat + " out of " + seatCount);
        }
        List<Stone> stones = new ArrayList<>();
        if (seatCount == 2) {
            for (int color = 0; color < 2; color++) {
                int seat = (firstSeat + color) % 2;
                for (Cell c : Setup.FIXED_BENT_2P.get(color)) {
                    stones.add(new Stone(stones.size(), seat, StoneKind.BENT, c.x(), c.y()));
                }
            }
        }
        List<Integer> queue = Setup.placementQueue(seatCount, firstSeat);
        List<Integer> jumps = IntStream.range(0, seatCount).map(i -> seatCount == 3 ? 1 : 0).boxed().toList();
        return new DambadukState(seatCount, firstSeat, Phase.PLACING, stones, List.of(), queue, queue.getFirst(),
                jumps, null, List.of(), stones.size(), 0, 0, null);
    }

    // ---------- 액션 ----------

    public Result apply(DambadukState state, int seat, DambadukAction action) {
        if (state.isOver()) {
            throw new DambadukActionRejectedException(RejectionReason.MATCH_OVER);
        }
        if (!state.isActive(seat) || seat != state.turnSeat()) {
            throw new DambadukActionRejectedException(RejectionReason.NOT_YOUR_TURN, "seat " + seat);
        }
        Table t = new Table(state);
        switch (action) {
            case DambadukAction.Place p -> {
                requirePhase(state, Phase.PLACING);
                t.place(seat, p.kind(), new Cell(p.x(), p.y()), false);
            }
            case DambadukAction.Move m -> {
                requirePhase(state, Phase.PLAYING);
                t.move(seat, m, false);
            }
            case DambadukAction.TimeoutAct __ -> {
                if (state.timeouts().get(seat) >= 1) {
                    t.eliminate(seat, ExitReason.TIMEOUT);              // 같은 사람 연속 2번째 (룰 §8-3)
                } else if (state.phase() == Phase.PLACING) {
                    StoneKind kind = placeKinds(state, seat).getFirst();
                    t.place(seat, kind, timeoutCell(state), true);
                } else {
                    t.move(seat, timeoutMove(state, seat), true);
                }
            }
        }
        return new Result(t.freeze(), t.events);
    }

    private static void requirePhase(DambadukState state, Phase phase) {
        if (state.phase() != phase) {
            throw new DambadukActionRejectedException(RejectionReason.WRONG_PHASE, state.phase() + " ≠ " + phase);
        }
    }

    // ---------- 탈주 (룰 §9) ----------

    public Desertion desert(DambadukState state, int seat) {
        if (state.isOver() || !state.isActive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        Table t = new Table(state);
        t.eliminate(seat, ExitReason.DESERTED);
        DambadukState next = t.freeze();
        return new Desertion(next, next.isOver() ? Desertion.Outcome.MATCH_ENDED : Desertion.Outcome.CONTINUED,
                t.events);
    }

    // ---------- 질의 ----------

    public List<Integer> pendingSeats(DambadukState state) {
        return state.isOver() ? List.of() : List.of(state.turnSeat());
    }

    /** 그 좌석이 지금 할 수 있는 액션 전부 — 배치는 종류(곧은돌 먼저) × 빈 칸(칸 번호 순), 옮김은 {@link #options} × 담 면. */
    public List<DambadukAction> legalActions(DambadukState state, int seat) {
        if (state.isOver() || seat != state.turnSeat()) {
            return List.of();
        }
        List<DambadukAction> out = new ArrayList<>();
        if (state.phase() == Phase.PLACING) {
            for (StoneKind kind : placeKinds(state, seat)) {
                for (int idx = 0; idx < Grid.CELLS; idx++) {
                    Cell c = Cell.ofIndex(idx);
                    if (state.stoneAt(c).isEmpty()) {
                        out.add(new DambadukAction.Place(kind, c.x(), c.y()));
                    }
                }
            }
            return out;
        }
        for (MoveOption o : options(state, seat)) {
            for (Dir w : o.walls()) {
                out.add(new DambadukAction.Move(o.stoneId(), o.path(), w, o.jump()));
            }
        }
        return out;
    }

    /** 배치 단계에서 그 좌석이 아직 놓을 수 있는 종류(곧은돌 먼저). 차례가 아니거나 배치 단계가 아니면 빈 목록. */
    public List<StoneKind> placeKinds(DambadukState state, int seat) {
        if (state.isOver() || state.phase() != Phase.PLACING || seat != state.turnSeat()) {
            return List.of();
        }
        List<StoneKind> out = new ArrayList<>(2);
        for (StoneKind kind : StoneKind.values()) {
            if (remaining(state.stones(), state.seatCount(), seat, kind) > 0) {
                out.add(kind);
            }
        }
        return out;
    }

    /** 그 좌석이 지금 고를 수 있는 옮김(룰 §4) — 차례·단계와 무관하게 판만 보고 계산한다(봇·건너뜀·굳음 판정이 같이 쓴다). */
    public List<MoveOption> options(DambadukState state, int seat) {
        if (!state.isActive(seat)) {
            return List.of();
        }
        return MoveRules.options(state.stones(), seat, state.wallSet(), state.jumpsLeft().get(seat) > 0, false);
    }

    public Territory territory(DambadukState state) {
        return TerritoryRule.compute(state.wallSet(), state.stones(), state.seatCount());
    }

    /** 시간 초과 때 서버가 두는 액션 (룰 §8). */
    public DambadukAction timeoutAction(DambadukState state, int seat) {
        return new DambadukAction.TimeoutAct();
    }

    /** 시간 초과 배치 칸 (룰 §8-1) — D4 에서 맨해튼 거리가 가장 가까운 빈 칸, 같으면 y·x 가 작은 쪽. */
    public Cell timeoutCell(DambadukState state) {
        Cell best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int idx = 0; idx < Grid.CELLS; idx++) {              // 칸 번호 순 = y → x 순, 먼저 찾은 쪽이 동률에서 이긴다
            Cell c = Cell.ofIndex(idx);
            if (state.stoneAt(c).isPresent()) {
                continue;
            }
            int d = c.manhattan(CENTER);
            if (d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No empty cell for a timeout placement");
        }
        return best;
    }

    /** 시간 초과 옮김 (룰 §8-1) — 정렬한 합법 수의 첫 수, 담 넘기는 넘기 없는 수가 하나도 없을 때만. */
    public DambadukAction.Move timeoutMove(DambadukState state, int seat) {
        List<MoveOption> options = options(state, seat);
        MoveOption pick = options.stream().filter(o -> !o.jump()).findFirst()
                .orElseGet(() -> options.isEmpty() ? null : options.getFirst());
        if (pick == null) {
            throw new IllegalStateException("No legal move for a timeout — the turn should have been skipped");
        }
        return new DambadukAction.Move(pick.stoneId(), pick.path(), pick.walls().getFirst(), pick.jump());
    }

    public DambadukPublicView publicView(DambadukState state) {
        List<Integer> colors = IntStream.range(0, state.seatCount()).map(state::colorIndex).boxed().toList();
        int turn = state.turnSeat();
        List<MoveOption> opts = !state.isOver() && state.phase() == Phase.PLAYING ? options(state, turn) : List.of();
        return new DambadukPublicView(state.seatCount(), state.firstSeat(), colors, state.phase(), state.stones(),
                state.walls(), state.placementQueue(), turn, state.jumpsLeft(), state.timeouts(), state.exits(),
                state.moveCount(), territory(state), placeKinds(state, turn), opts, state.result());
    }

    /**
     * 굳음 (룰 §6 ②) — 다툼 터가 있고, 그 안의 어느 돌도 옮길 수 없다(그 돌 주인의 남은 담 넘기 포함). 다툼 터가 없으면 false.
     */
    public static boolean isFrozen(List<Stone> stones, Walls walls, List<Integer> jumpsLeft, Territory territory,
                                   Set<Integer> activeSeats) {
        Partition p = Partition.of(walls);
        Set<Integer> contestedRegions = new HashSet<>();
        for (int i = 0; i < territory.regions().size(); i++) {
            if (territory.regions().get(i).kind() == RegionKind.CONTESTED) {
                contestedRegions.add(i);
            }
        }
        if (contestedRegions.isEmpty()) {
            return false;
        }
        Set<Integer> movable = new HashSet<>();
        for (int seat : activeSeats) {
            for (MoveOption o : MoveRules.options(stones, seat, walls, jumpsLeft.get(seat) > 0, false)) {
                movable.add(o.stoneId());
            }
        }
        for (Stone s : stones) {
            if (contestedRegions.contains(p.regionOf(s.cell())) && movable.contains(s.id())) {
                return false;
            }
        }
        return true;
    }

    /** 결과 순위 (룰 §7) — 빠지지 않은 사람은 (점수, 가장 큰 집) 높은 순, 같으면 동순위. 빠진 사람은 그 아래 동순위. */
    public static List<Standing> standings(Territory territory, int seatCount, List<Exit> exits) {
        Set<Integer> out = new HashSet<>();
        exits.forEach(e -> out.add(e.seat()));
        int activeCount = seatCount - out.size();
        List<Standing> standings = new ArrayList<>(seatCount);
        for (int seat = 0; seat < seatCount; seat++) {
            int score = territory.scores().get(seat);
            int largest = territory.largest().get(seat);
            if (out.contains(seat)) {
                int s = seat;
                ExitReason why = exits.stream().filter(e -> e.seat() == s).findFirst().orElseThrow().reason();
                standings.add(new Standing(seat, score, largest, activeCount + 1, why));
                continue;
            }
            int better = 0;
            for (int other = 0; other < seatCount; other++) {
                if (other == seat || out.contains(other)) {
                    continue;
                }
                int os = territory.scores().get(other);
                int ol = territory.largest().get(other);
                if (os > score || (os == score && ol > largest)) {
                    better++;
                }
            }
            standings.add(new Standing(seat, score, largest, 1 + better, null));
        }
        return standings;
    }

    private static int remaining(List<Stone> stones, int seatCount, int seat, StoneKind kind) {
        long have = stones.stream().filter(s -> s.seat() == seat && s.kind() == kind).count();
        return Setup.quota(seatCount, kind) - (int) have;
    }

    // ---------- 가변 사본 ----------

    private static final class Table {
        final int n;
        final int first;
        Phase phase;
        final List<Stone> stones;
        Walls walls;
        final List<Integer> queue;
        int turn;
        final int[] jumps;
        final int[] timeouts;
        final List<Exit> exits;
        int nextId;
        int moves;
        int jumpCount;
        MatchResult result;
        final List<DambadukEvent> events = new ArrayList<>();

        Table(DambadukState s) {
            n = s.seatCount();
            first = s.firstSeat();
            phase = s.phase();
            stones = new ArrayList<>(s.stones());
            walls = s.wallSet();
            queue = new ArrayList<>(s.placementQueue());
            turn = s.turnSeat();
            jumps = s.jumpsLeft().stream().mapToInt(Integer::intValue).toArray();
            timeouts = s.timeouts().stream().mapToInt(Integer::intValue).toArray();
            exits = new ArrayList<>(s.exits());
            nextId = s.nextStoneId();
            moves = s.moveCount();
            jumpCount = s.jumpCount();
            result = s.result();
        }

        DambadukState freeze() {
            return new DambadukState(n, first, phase, stones, walls.toList(), queue, turn, toList(jumps),
                    toList(timeouts), exits, nextId, moves, jumpCount, result);
        }

        boolean active(int seat) {
            return exits.stream().noneMatch(e -> e.seat() == seat);
        }

        Set<Integer> activeSet() {
            Set<Integer> out = new HashSet<>();
            for (int s = 0; s < n; s++) {
                if (active(s)) {
                    out.add(s);
                }
            }
            return out;
        }

        List<Integer> jumpsList() {
            return toList(jumps);
        }

        // ----- 배치 (룰 §3) -----

        void place(int seat, StoneKind kind, Cell c, boolean timedOut) {
            if (!c.onBoard()) {
                throw new DambadukActionRejectedException(RejectionReason.OUT_OF_BOARD, c.toString());
            }
            if (kind == null || remaining(stones, n, seat, kind) <= 0) {
                throw new DambadukActionRejectedException(RejectionReason.NO_STONE_LEFT, String.valueOf(kind));
            }
            if (stones.stream().anyMatch(s -> s.x() == c.x() && s.y() == c.y())) {
                throw new DambadukActionRejectedException(RejectionReason.OCCUPIED, c.toString());
            }
            int id = nextId++;
            stones.add(new Stone(id, seat, kind, c.x(), c.y()));
            queue.removeFirst();
            timeouts[seat] = timedOut ? timeouts[seat] + 1 : 0;
            events.add(new DambadukEvent.StonePlaced(seat, id, kind, c.x(), c.y(), timedOut));
            afterPlacingStep();
        }

        /** 배치가 남았으면 다음 사람, 끝났으면 선부터 옮김 단계. */
        void afterPlacingStep() {
            if (!queue.isEmpty()) {
                turn = queue.getFirst();
                emitTurn();
                return;
            }
            phase = Phase.PLAYING;
            proceed(first);
        }

        // ----- 옮김 (룰 §4) -----

        void move(int seat, DambadukAction.Move m, boolean timedOut) {
            Stone stone = stones.stream().filter(s -> s.id() == m.stoneId()).findFirst().orElse(null);
            if (stone == null || stone.seat() != seat) {
                throw new DambadukActionRejectedException(RejectionReason.NOT_YOUR_STONE, "stone " + m.stoneId());
            }
            if (!Paths.validShape(stone.kind(), m.path())) {
                throw new DambadukActionRejectedException(RejectionReason.BAD_PATH, stone.kind() + " " + m.path());
            }
            if (m.jump() && jumps[seat] == 0) {
                throw new DambadukActionRejectedException(RejectionReason.JUMP_UNAVAILABLE);
            }
            Walk w = MoveRules.walk(stone.cell(), m.path(), walls, MoveRules.occupancy(stones, stone.id()));
            if (w.blocked()) {
                throw new DambadukActionRejectedException(RejectionReason.PATH_BLOCKED, stone.cell() + " " + m.path());
            }
            int crossed = w.crossed().size();
            if ((!m.jump() && crossed > 0) || crossed > 1) {
                throw new DambadukActionRejectedException(RejectionReason.WALL_IN_PATH, "walls " + w.crossed());
            }
            if (m.jump() && crossed == 0) {
                throw new DambadukActionRejectedException(RejectionReason.JUMP_WITHOUT_WALL);
            }
            int jumped = m.jump() ? w.crossed().getFirst() : -1;
            Walls after = m.jump() ? walls.without(jumped) : walls;
            int face = m.wall() == null ? -1 : Grid.face(w.to(), m.wall());
            if (face < 0 || after.has(face)) {
                throw new DambadukActionRejectedException(RejectionReason.WALL_SIDE_TAKEN, w.to() + " " + m.wall());
            }

            stones.set(stones.indexOf(stone), stone.movedTo(w.to()));
            walls = after.with(face);
            if (m.jump()) {
                jumps[seat] = 0;
                jumpCount++;
            }
            moves++;
            timeouts[seat] = timedOut ? timeouts[seat] + 1 : 0;
            events.add(new DambadukEvent.StoneMoved(seat, stone.id(), m.path(), stone.x(), stone.y(), w.to().x(),
                    w.to().y(), m.wall(), face, jumped, timedOut));
            proceed((seat + 1) % n);
        }

        /**
         * 옮김 단계의 다음 (룰 §4.6·§6) — 끝을 보고, 아니면 {@code start} 부터 좌석 +1 로 옮길 수 있는 사람을 찾는다(못 옮기는 사람은
         * 건너뜀). 굳음 판정이 "다툼 터의 어떤 돌은 움직인다"를 보장하므로 반드시 찾는다.
         */
        void proceed(int start) {
            Set<Integer> active = activeSet();
            if (n == 3 && active.size() == 1) {
                end(EndReason.LAST_STANDING, -1);
                return;
            }
            Territory terr = TerritoryRule.compute(walls, stones, n);
            if (!terr.hasContested()) {
                end(EndReason.SEPARATED, -1);
                return;
            }
            if (isFrozen(stones, walls, jumpsList(), terr, active)) {
                end(EndReason.FROZEN, -1);
                return;
            }
            for (int k = 0; k < n; k++) {
                int s = (start + k) % n;
                if (!active.contains(s)) {
                    continue;
                }
                List<MoveOption> opts = MoveRules.options(stones, s, walls, jumps[s] > 0, false);
                if (!opts.isEmpty()) {
                    turn = s;
                    events.add(new DambadukEvent.TurnChanged(s, Phase.PLAYING, terr, List.of(), opts));
                    return;
                }
                events.add(new DambadukEvent.TurnSkipped(s));
            }
            throw new IllegalStateException("Nobody can move but the match is neither separated nor frozen");
        }

        void emitTurn() {
            Territory terr = TerritoryRule.compute(walls, stones, n);
            List<StoneKind> kinds = new ArrayList<>(2);
            for (StoneKind kind : StoneKind.values()) {
                if (remaining(stones, n, turn, kind) > 0) {
                    kinds.add(kind);
                }
            }
            events.add(new DambadukEvent.TurnChanged(turn, Phase.PLACING, terr, kinds, List.of()));
        }

        // ----- 빠짐 (룰 §8·§9) -----

        void eliminate(int seat, ExitReason reason) {
            exits.add(new Exit(seat, reason));
            if (n == 2) {
                events.add(new DambadukEvent.SeatOut(seat, reason, List.of()));
                end(reason == ExitReason.TIMEOUT ? EndReason.TIMEOUT : EndReason.DESERTED, seat);
                return;
            }
            List<Integer> removed = stones.stream().filter(s -> s.seat() == seat).map(Stone::id).toList();
            stones.removeIf(s -> s.seat() == seat);
            queue.removeIf(s -> s == seat);
            events.add(new DambadukEvent.SeatOut(seat, reason, removed));
            if (activeSet().size() == 1) {
                end(EndReason.LAST_STANDING, -1);
                return;
            }
            if (phase == Phase.PLACING) {
                afterPlacingStep();
                return;
            }
            proceed(turn == seat ? (seat + 1) % n : turn);
        }

        // ----- 끝 (룰 §6·§7) -----

        void end(EndReason reason, int causeSeat) {
            Territory terr = TerritoryRule.compute(walls, stones, n);
            List<Standing> standings = standings(terr, n, exits);
            List<Integer> ranked = standings.stream().filter(s -> s.exit() == null && s.rank() == 1)
                    .map(Standing::seat).toList();
            boolean draw = exits.isEmpty() && ranked.size() == n;
            List<Integer> winners = draw ? List.of() : ranked;
            result = new MatchResult(reason, standings, winners, draw, causeSeat);
            phase = Phase.ENDED;
            turn = -1;
            events.add(new DambadukEvent.MatchEnded(reason, standings, winners, draw, causeSeat, terr, moves));
        }

        private static List<Integer> toList(int[] a) {
            List<Integer> out = new ArrayList<>(a.length);
            for (int v : a) {
                out.add(v);
            }
            return out;
        }
    }

}
