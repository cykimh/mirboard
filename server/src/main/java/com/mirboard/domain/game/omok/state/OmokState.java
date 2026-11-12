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
 * @param slams     좌석별 강하게 두기 사용 수 (룰 §4.1, D-193) — 옛 JSON 에 없으면 0
 * @param boardSize 판 줄 수 — 늘 {@link Board#SIZE}(19). 옛 JSON(15줄 시절)에는 없다: 그 판은 수순을 19줄 판 가운데로 옮겨 이어 둔다
 *                  (D-193 — 배포 순간에 진행 중이던 판만 해당, 가장자리가 넓어지는 것 말고는 그대로)
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
                        MatchResult result,
                        List<Integer> slams,
                        Integer boardSize) implements GameState {

    /** 좌석마다 한 판에 쓸 수 있는 강하게 두기 수 (룰 §4.1, D-193). */
    public static final int SLAMS_PER_MATCH = 1;

    public OmokState {
        moves = moves == null ? List.of() : List.copyOf(moves);
        timeouts = timeouts == null ? zeros(mode.seatCount()) : List.copyOf(timeouts);
        slams = slams == null ? zeros(mode.seatCount()) : List.copyOf(slams);
        if (boardSize == null) {                       // 15줄 시절 JSON — 19줄 판 가운데로 옮긴다
            int shift = (Board.SIZE - Board.LEGACY_SIZE) / 2;
            moves = moves.stream().map(m -> m.shifted(shift)).toList();
            result = result == null ? null : result.shifted(shift);
            boardSize = Board.SIZE;
        }
        if (boardSize != Board.SIZE) {
            throw new IllegalArgumentException("Unsupported omok board size " + boardSize);
        }
    }

    /** 이 판의 상태 — 판 크기는 늘 지금 판({@link Board#SIZE}). */
    public OmokState(OmokMode mode, OmokRule rule, int blackTeam, int firstSeat, List<Move> moves, int turnSeat,
                     List<Integer> timeouts, MatchResult result, List<Integer> slams) {
        this(mode, rule, blackTeam, firstSeat, moves, turnSeat, timeouts, result, slams, Board.SIZE);
    }

    /** 강하게 두기 이전의 모양 — 사용 수는 0 으로 시작한다. */
    public OmokState(OmokMode mode, OmokRule rule, int blackTeam, int firstSeat, List<Move> moves, int turnSeat,
                     List<Integer> timeouts, MatchResult result) {
        this(mode, rule, blackTeam, firstSeat, moves, turnSeat, timeouts, result, null, Board.SIZE);
    }

    private static List<Integer> zeros(int n) {
        return IntStream.range(0, n).map(i -> 0).boxed().toList();
    }

    /** 그 좌석이 이 판에서 아직 강하게 둘 수 있는 수. */
    public int slamsLeft(int seat) {
        return Math.max(0, SLAMS_PER_MATCH - slams.get(seat));
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
        return withMove(move, nextTurn, nextTimeouts, nextResult, slams);
    }

    public OmokState withMove(Move move, int nextTurn, List<Integer> nextTimeouts, MatchResult nextResult,
                              List<Integer> nextSlams) {
        List<Move> next = new ArrayList<>(moves);
        next.add(move);
        return new OmokState(mode, rule, blackTeam, firstSeat, next, nextTurn, nextTimeouts, nextResult, nextSlams);
    }

    public OmokState ended(MatchResult r) {
        return new OmokState(mode, rule, blackTeam, firstSeat, moves, -1, timeouts, r, slams);
    }
}
