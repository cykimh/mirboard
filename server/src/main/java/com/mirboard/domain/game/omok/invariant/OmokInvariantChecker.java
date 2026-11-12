package com.mirboard.domain.game.omok.invariant;

import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.rules.TurnOrder;
import com.mirboard.domain.game.omok.rules.WinRule;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.MatchResult;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 오목 불변식 (룰 §13). 시뮬레이션이 매 전이 뒤 부른다 — 위반이면 {@link IllegalStateException}.
 *
 * <p>수순을 처음부터 다시 두며 확인한다: 자리가 판 안·빈칸 · 좌석이 첫 수 좌석부터 +1 순 · 색이 흑부터 번갈고 그 좌석의 색 ·
 * 흑 수가 그 순간의 금수 자리가 아님 · 마지막 수 전에는 이기는 줄이 없음. 그 뒤 차례·시간 초과 수·결과의 짝을 본다.
 */
public final class OmokInvariantChecker {

    private OmokInvariantChecker() {
    }

    public static void check(OmokState s) {
        int n = s.seatCount();
        require(s.timeouts().size() == n, "timeouts size " + s.timeouts().size() + " != seats " + n);
        require(s.blackTeam() == 0 || s.blackTeam() == 1, "blackTeam " + s.blackTeam());
        require(TurnOrder.team(s.firstSeat()) == s.blackTeam(), "first seat not on black team");

        Board board = Board.empty();
        Set<Point> seen = new HashSet<>();
        List<Move> moves = s.moves();
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            Point p = m.point();
            require(p.onBoard(), "move " + (i + 1) + " off board " + p);
            require(seen.add(p), "move " + (i + 1) + " on occupied " + p);
            int expectedSeat = (s.firstSeat() + i) % n;
            require(m.seat() == expectedSeat, "move " + (i + 1) + " seat " + m.seat() + " != " + expectedSeat);
            Stone color = i % 2 == 0 ? Stone.BLACK : Stone.WHITE;
            require(s.colorOf(m.seat()) == color, "move " + (i + 1) + " color mismatch for seat " + m.seat());
            if (color == Stone.BLACK) {
                require(ForbiddenRule.check(board, p, s.rule()).isEmpty(),
                        "move " + (i + 1) + " is a forbidden point " + p);
            }
            board = board.with(p, color);
            boolean wins = WinRule.winLine(board, p, s.rule()).isPresent();
            if (i < moves.size() - 1) {
                require(!wins, "move " + (i + 1) + " won but the match went on");
            }
        }

        for (int t : s.timeouts()) {
            require(t >= 0 && t <= (s.isOver() ? 2 : 1), "timeout count " + t);
        }

        MatchResult r = s.result();
        if (r == null) {
            require(s.turnSeat() == (s.firstSeat() + moves.size()) % n, "turn seat " + s.turnSeat());
            require(!board.isFull(), "full board but not over");
            if (!moves.isEmpty()) {
                require(WinRule.winLine(board, moves.getLast().point(), s.rule()).isEmpty(), "unended five");
            }
            return;
        }
        require(s.turnSeat() == -1, "ended match has turn seat " + s.turnSeat());
        require(r.isDraw() == r.reason().isDraw(), "draw flag vs reason " + r.reason());
        if (r.isDraw()) {
            require(r.winnerSeats().isEmpty(), "draw with winners");
            if (r.reason() == EndReason.BOARD_FULL) {
                require(board.isFull(), "BOARD_FULL but " + moves.size() + " moves");
            }
            return;
        }
        int winnerTeam = r.winnerColor() == Stone.BLACK ? s.blackTeam() : 1 - s.blackTeam();
        require(r.winnerSeats().equals(s.seatsOfTeam(winnerTeam)), "winner seats " + r.winnerSeats());
        switch (r.reason()) {
            case FIVE -> {
                require(!moves.isEmpty(), "FIVE without moves");
                Move last = moves.getLast();
                require(s.colorOf(last.seat()) == r.winnerColor(), "FIVE by the loser");
                require(r.winLine().equals(WinRule.winLine(board, last.point(), s.rule()).orElse(List.of())),
                        "win line mismatch");
                require(r.causeSeat() == -1, "FIVE with cause seat");
            }
            case RESIGN, TIMEOUT, DESERTED -> {
                require(r.causeSeat() >= 0 && r.causeSeat() < n, "cause seat " + r.causeSeat());
                require(TurnOrder.team(r.causeSeat()) != winnerTeam, "cause seat on the winning team");
                require(r.winLine().isEmpty(), "win line on " + r.reason());
            }
            default -> throw new IllegalStateException("draw reason with a winner: " + r.reason());
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("Omok invariant violated: " + message);
        }
    }
}
