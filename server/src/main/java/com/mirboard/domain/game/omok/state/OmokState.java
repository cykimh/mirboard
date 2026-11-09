package com.mirboard.domain.game.omok.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.TurnOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 오목 판 상태 (설계서 §4.2). 불변 레코드이고 저장은 수순뿐 — 판은 {@link #board()} 가 수순에서 다시 만든다(최대 225수라
 * 매 전이 재구성이 싸다).
 *
 * @param blackTeam 흑 팀({@code seat % 2}). 1:1 이면 곧 흑 좌석
 * @param firstSeat 첫 수를 둔(둘) 좌석 — 흑 팀이다
 * @param turnSeat  지금 둘 좌석, 끝났으면 −1
 * @param timeouts  좌석별 연속 시간 초과 수 (룰 §8)
 * @param result    끝나기 전에는 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OmokState(OmokMode mode,
                        OmokRule rule,
                        int blackTeam,
                        int firstSeat,
                        List<Move> moves,
                        int turnSeat,
                        List<Integer> timeouts,
                        MatchResult result) implements GameState {

    public OmokState {
        moves = moves == null ? List.of() : List.copyOf(moves);
        timeouts = timeouts == null
                ? IntStream.range(0, mode.seatCount()).map(i -> 0).boxed().toList()
                : List.copyOf(timeouts);
    }

    public int seatCount() {
        return mode.seatCount();
    }

    public boolean isOver() {
        return result != null;
    }

    public Stone colorOf(int seat) {
        return TurnOrder.colorOf(seat, blackTeam);
    }

    /** 다음 수의 색 — 흑부터 번갈아. */
    public Stone nextColor() {
        return moves.size() % 2 == 0 ? Stone.BLACK : Stone.WHITE;
    }

    public Board board() {
        Board b = Board.empty();
        for (int i = 0; i < moves.size(); i++) {
            b = b.with(moves.get(i).point(), i % 2 == 0 ? Stone.BLACK : Stone.WHITE);
        }
        return b;
    }

    /** 같은 팀({@code seat % 2})의 좌석들, 오름차순. */
    public List<Integer> seatsOfTeam(int team) {
        return IntStream.range(0, seatCount()).filter(s -> TurnOrder.team(s) == team).boxed().toList();
    }

    public OmokState withMove(Move move, int nextTurn, List<Integer> nextTimeouts, MatchResult nextResult) {
        List<Move> next = new ArrayList<>(moves);
        next.add(move);
        return new OmokState(mode, rule, blackTeam, firstSeat, next, nextTurn, nextTimeouts, nextResult);
    }

    public OmokState ended(MatchResult r) {
        return new OmokState(mode, rule, blackTeam, firstSeat, moves, -1, timeouts, r);
    }
}
