package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.ArrayList;
import java.util.List;

/** 엔진 테스트용 상태 만들기. */
public final class OmokStates {

    public static final OmokEngine ENGINE = new OmokEngine();

    private OmokStates() {
    }

    /** 1:1, 흑 = 좌석 0. */
    public static OmokState solo(OmokRule rule) {
        return ENGINE.startMatch(OmokMode.SOLO, rule, 2, 0, 0);
    }

    /** 2:2, 흑 팀 0, 첫 수 좌석 2 → 차례 2,3,0,1. */
    public static OmokState teams(OmokRule rule) {
        return ENGINE.startMatch(OmokMode.TEAMS_2V2, rule, 4, 0, 2);
    }

    /** 지금 차례 좌석이 차례대로 둔다. */
    public static OmokState play(OmokState s, Point... points) {
        for (Point p : points) {
            s = ENGINE.apply(s, s.turnSeat(), new OmokAction.Place(p.x(), p.y())).newState();
        }
        return s;
    }

    public static Point p(int x, int y) {
        return new Point(x, y);
    }

    /**
     * 판 그림 그대로의 상태 — 흑 차례(백 수를 흑 수와 같게 맞추려고 모자란 백은 맨 위·아래 줄 짝수 칸에 채운다). 수순 순서는
     * 흑·백을 번갈아 놓을 뿐 실제 대국 순서가 아니다(금수 이력 검사가 필요한 테스트에는 쓰지 말 것).
     */
    public static OmokState blackToMove(OmokRule rule, Board board) {
        List<Point> blacks = new ArrayList<>();
        List<Point> whites = new ArrayList<>();
        for (int y = 0; y < Board.SIZE; y++) {
            for (int x = 0; x < Board.SIZE; x++) {
                Stone s = board.stoneAt(new Point(x, y));
                if (s == Stone.BLACK) {
                    blacks.add(new Point(x, y));
                } else if (s == Stone.WHITE) {
                    whites.add(new Point(x, y));
                }
            }
        }
        int[] padRows = {0, 14, 1, 13};
        outer:
        for (int row : padRows) {
            for (int x = 0; x < Board.SIZE && whites.size() < blacks.size(); x += 2) {
                Point pad = new Point(x, row);
                if (board.isEmpty(pad) && !whites.contains(pad)) {
                    whites.add(pad);
                }
                if (whites.size() == blacks.size()) {
                    break outer;
                }
            }
        }
        if (whites.size() != blacks.size()) {
            throw new IllegalArgumentException("cannot balance " + blacks.size() + " blacks vs " + whites.size() + " whites");
        }
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < blacks.size(); i++) {
            moves.add(new Move(0, blacks.get(i).x(), blacks.get(i).y(), false));
            moves.add(new Move(1, whites.get(i).x(), whites.get(i).y(), false));
        }
        return new OmokState(OmokMode.SOLO, rule, 0, 0, moves, 0, null, null);
    }
}
