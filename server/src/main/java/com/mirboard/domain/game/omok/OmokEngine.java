package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.action.OmokActionRejectedException;
import com.mirboard.domain.game.omok.action.RejectionReason;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.rules.ForbiddenPoint;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.rules.TurnOrder;
import com.mirboard.domain.game.omok.rules.WinRule;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.MatchResult;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokPublicView;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 오목 순수 룰 엔진 (룰 정본 {@code docs/rules-omok.md}, D-175~D-178). 저장소·시계·난수가 없다 — 색 추첨은 라운드
 * 스타터가 하고 결과를 {@link #startMatch} 인자로 넘긴다. 같은 입력이면 같은 결과다.
 */
public final class OmokEngine {

    /** 시간 초과 자동 수의 첫 수 자리 — 천원 (룰 §8). */
    public static final Point CENTER = new Point(7, 7);

    public record Result(OmokState newState, List<OmokEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    /** 탈주 결과 (룰 §9). 오목은 계속할 사람이 없으므로 끝내거나(이미 끝났으면) 해당 없음뿐이다. */
    public record Desertion(OmokState newState, boolean ended, List<OmokEvent> events) {
        public Desertion {
            events = List.copyOf(events);
        }
    }

    /**
     * 새 판 (룰 §3). 흑 팀과 첫 수 좌석은 호출자가 추첨해 넘긴다.
     *
     * @param seatCount 방의 실제 좌석 수 — 모드의 좌석 수와 같아야 한다
     */
    public OmokState startMatch(OmokMode mode, OmokRule rule, int seatCount, int blackTeam, int firstSeat) {
        if (seatCount != mode.seatCount()) {
            throw new IllegalArgumentException("Mode " + mode + " needs " + mode.seatCount() + " seats, got " + seatCount);
        }
        if (blackTeam != 0 && blackTeam != 1) {
            throw new IllegalArgumentException("blackTeam must be 0 or 1: " + blackTeam);
        }
        if (firstSeat < 0 || firstSeat >= seatCount || TurnOrder.team(firstSeat) != blackTeam) {
            throw new IllegalArgumentException("firstSeat " + firstSeat + " is not on the black team " + blackTeam);
        }
        return new OmokState(mode, rule, blackTeam, firstSeat, List.of(), firstSeat, null, null);
    }

    // ---------- 액션 ----------

    public Result apply(OmokState state, int seat, OmokAction action) {
        if (state.isOver()) {
            throw new OmokActionRejectedException(RejectionReason.MATCH_OVER);
        }
        if (seat < 0 || seat >= state.seatCount()) {
            throw new OmokActionRejectedException(RejectionReason.NOT_YOUR_TURN, "seat " + seat);
        }
        return switch (action) {
            case OmokAction.Resign __ -> lose(state, seat, EndReason.RESIGN);
            case OmokAction.Place place -> {
                requireTurn(state, seat);
                yield place(state, seat, new Point(place.x(), place.y()), false);
            }
            case OmokAction.TimeoutPlace __ -> {
                requireTurn(state, seat);
                if (state.timeouts().get(seat) >= 1) {
                    yield lose(state, seat, EndReason.TIMEOUT);       // 같은 사람 연속 2번째 (룰 §8-4)
                }
                yield place(state, seat, timeoutPoint(state), true);
            }
        };
    }

    private static void requireTurn(OmokState state, int seat) {
        if (seat != state.turnSeat()) {
            throw new OmokActionRejectedException(RejectionReason.NOT_YOUR_TURN, "seat " + seat);
        }
    }

    private Result place(OmokState state, int seat, Point p, boolean timedOut) {
        if (!p.onBoard()) {
            throw new OmokActionRejectedException(RejectionReason.OUT_OF_BOARD, p.toString());
        }
        Board board = state.board();
        if (!board.isEmpty(p)) {
            throw new OmokActionRejectedException(RejectionReason.OCCUPIED, p.toString());
        }
        Stone color = state.colorOf(seat);
        if (color == Stone.BLACK) {
            ForbiddenRule.check(board, p, state.rule()).ifPresent(kind -> {
                throw new OmokActionRejectedException(RejectionReason.FORBIDDEN_MOVE, kind + " at " + p);
            });
        }

        Board after = board.with(p, color);
        List<Integer> timeouts = new ArrayList<>(state.timeouts());
        timeouts.set(seat, timedOut ? timeouts.get(seat) + 1 : 0);
        Move move = new Move(seat, p.x(), p.y(), timedOut);
        int moveNumber = state.moves().size() + 1;

        List<OmokEvent> events = new ArrayList<>();
        events.add(new OmokEvent.StonePlaced(seat, color, p.x(), p.y(), moveNumber, timedOut));

        Optional<List<Point>> winLine = WinRule.winLine(after, p, state.rule());
        if (winLine.isPresent()) {
            MatchResult r = new MatchResult(color, state.seatsOfTeam(TurnOrder.team(seat)), EndReason.FIVE, -1,
                    winLine.get());
            return finish(state.withMove(move, -1, timeouts, r), events);
        }
        if (after.isFull()) {
            return finish(state.withMove(move, -1, timeouts, draw(EndReason.BOARD_FULL)), events);
        }
        int next = TurnOrder.next(seat, state.seatCount());
        Stone nextColor = state.colorOf(next);
        List<ForbiddenPoint> forbidden = nextColor == Stone.BLACK ? ForbiddenRule.all(after, state.rule()) : List.of();
        if (nextColor == Stone.BLACK && forbidden.size() == Board.CELLS - after.stoneCount()) {
            return finish(state.withMove(move, -1, timeouts, draw(EndReason.NO_LEGAL_MOVE)), events);
        }
        events.add(new OmokEvent.TurnChanged(next, nextColor, forbidden));
        return new Result(state.withMove(move, next, timeouts, null), events);
    }

    /** 그 좌석의 편이 진다 — 기권·시간패·탈주 (룰 §7~§9). */
    private Result lose(OmokState state, int seat, EndReason reason) {
        int winnerTeam = 1 - TurnOrder.team(seat);
        Stone winnerColor = winnerTeam == state.blackTeam() ? Stone.BLACK : Stone.WHITE;
        MatchResult r = new MatchResult(winnerColor, state.seatsOfTeam(winnerTeam), reason, seat, List.of());
        return finish(state.ended(r), List.of());
    }

    private static MatchResult draw(EndReason reason) {
        return new MatchResult(null, List.of(), reason, -1, List.of());
    }

    private static Result finish(OmokState ended, List<OmokEvent> before) {
        MatchResult r = ended.result();
        List<OmokEvent> events = new ArrayList<>(before);
        events.add(new OmokEvent.MatchEnded(r.winnerColor(), r.winnerSeats(), r.reason(), r.causeSeat(), r.winLine(),
                ended.moves().size()));
        return new Result(ended, events);
    }

    // ---------- 탈주 ----------

    /** 탈주 확정 (룰 §9) — 그 편 패, 판 종료. 이미 끝났으면 해당 없음. */
    public Desertion desert(OmokState state, int seat) {
        if (state.isOver() || seat < 0 || seat >= state.seatCount()) {
            return new Desertion(state, false, List.of());
        }
        Result r = lose(state, seat, EndReason.DESERTED);
        return new Desertion(r.newState(), true, r.events());
    }

    // ---------- 질의 ----------

    public List<Integer> pendingSeats(OmokState state) {
        return state.isOver() ? List.of() : List.of(state.turnSeat());
    }

    /** 그 좌석이 지금 둘 수 있는 자리 전부(y·x 순). 기권은 넣지 않는다 — 포트 기본 봇이 기권을 고르지 않게(설계서 §3.1-6). */
    public List<OmokAction> legalActions(OmokState state, int seat) {
        if (state.isOver() || seat != state.turnSeat()) {
            return List.of();
        }
        return legalPoints(state).stream().<OmokAction>map(p -> new OmokAction.Place(p.x(), p.y())).toList();
    }

    /** 지금 차례가 둘 수 있는 빈칸(y·x 순) — 흑이면 금수 제외. */
    public List<Point> legalPoints(OmokState state) {
        Board board = state.board();
        Set<Point> forbidden = new HashSet<>();
        if (state.nextColor() == Stone.BLACK) {
            for (ForbiddenPoint f : ForbiddenRule.all(board, state.rule())) {
                forbidden.add(new Point(f.x(), f.y()));
            }
        }
        List<Point> out = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                Point p = new Point(x, y);
                if (board.isEmpty(p) && !forbidden.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 시간 초과 때 서버가 두는 액션 (룰 §8). */
    public OmokAction timeoutAction(OmokState state, int seat) {
        return new OmokAction.TimeoutPlace();
    }

    /**
     * 시간 초과 자동 수의 자리 (룰 §8-1) — 직전 수에서 체스판 거리가 가장 가까운 합법 빈칸, 같으면 y·x 가 작은 쪽. 첫 수면
     * 천원. 흑이면 금수 자리는 고르지 않는다.
     */
    public Point timeoutPoint(OmokState state) {
        List<Point> legal = legalPoints(state);
        if (state.moves().isEmpty()) {
            return legal.contains(CENTER) ? CENTER : legal.getFirst();
        }
        Point last = state.moves().getLast().point();
        Point best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Point p : legal) {                         // legal 은 y·x 순 — 먼저 찾은 쪽이 동률에서 이긴다
            int d = p.chebyshev(last);
            if (d < bestDistance) {
                best = p;
                bestDistance = d;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No legal point for timeout — the match should have ended");
        }
        return best;
    }

    /** 지금 판의 금수 자리 — 흑 차례일 때만, 그 밖은 빈 목록. */
    public List<ForbiddenPoint> forbiddenPoints(OmokState state) {
        if (state.isOver() || state.nextColor() != Stone.BLACK) {
            return List.of();
        }
        return ForbiddenRule.all(state.board(), state.rule());
    }

    public OmokPublicView publicView(OmokState state) {
        List<Stone> colors = IntStream.range(0, state.seatCount()).mapToObj(state::colorOf).toList();
        return new OmokPublicView(state.mode(), state.rule(), colors, state.moves(), state.turnSeat(),
                forbiddenPoints(state), state.timeouts(), state.result());
    }
}
