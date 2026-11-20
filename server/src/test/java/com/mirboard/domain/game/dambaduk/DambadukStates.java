package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;

/** 엔진 테스트용 상태 만들기. */
public final class DambadukStates {

    public static final DambadukEngine ENGINE = DambadukBoards.ENGINE;

    private DambadukStates() {
    }

    /** 2인, 선 = 좌석 0. 곧은돌은 아직. */
    public static DambadukState two() {
        return ENGINE.startMatch(2, 0);
    }

    /** 3인, 선 = 좌석 0. */
    public static DambadukState three() {
        return ENGINE.startMatch(3, 0);
    }

    /** 2인 배치를 다 한다 — 뱀 순서(0,1,1,0)로 곧은돌을 C4·E4·D3·D5 에. 옮김 단계, 좌석 0 차례. */
    public static DambadukState twoPlaying() {
        return place(two(), "C4", "E4", "D3", "D5");
    }

    /** 3인 배치를 다 한다 — 뱀 순서(0,1,2,2,1,0)로 곧은·곧은·곧은·꺾은·꺾은·꺾은을 B2·F2·D4·D6·B6·F6 에. */
    public static DambadukState threePlaying() {
        DambadukState s = three();
        String[] cells = {"B2", "F2", "D4", "D6", "B6", "F6"};
        StoneKind[] kinds = {StoneKind.STRAIGHT, StoneKind.STRAIGHT, StoneKind.STRAIGHT, StoneKind.BENT,
                StoneKind.BENT, StoneKind.BENT};
        for (int i = 0; i < cells.length; i++) {
            Cell c = DambadukBoards.c(cells[i]);
            s = ENGINE.apply(s, s.turnSeat(), new DambadukAction.Place(kinds[i], c.x(), c.y())).newState();
        }
        return s;
    }

    /** 지금 차례 좌석이 차례대로 곧은돌을 놓는다(3인이면 남은 종류 중 곧은돌 먼저). */
    public static DambadukState place(DambadukState s, String... labels) {
        for (String label : labels) {
            Cell c = DambadukBoards.c(label);
            StoneKind kind = ENGINE.placeKinds(s, s.turnSeat()).getFirst();
            s = ENGINE.apply(s, s.turnSeat(), new DambadukAction.Place(kind, c.x(), c.y())).newState();
        }
        return s;
    }

    public static DambadukAction.Move move(int stoneId, Dir wall, Dir... path) {
        return new DambadukAction.Move(stoneId, List.of(path), wall, false);
    }

    public static DambadukAction.Move jump(int stoneId, Dir wall, Dir... path) {
        return new DambadukAction.Move(stoneId, List.of(path), wall, true);
    }
}
