package com.mirboard.domain.game.dambaduk.rules;

import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.List;

/** 배치 준비 (룰 §3) — 한 사람의 돌 수, 2인 고정 꺾은돌 칸, 뱀 순서. */
public final class Setup {

    /** 2인 고정 꺾은돌 — 색 번호(0 흑, 1 백)별 칸. 흑 B2·F6, 백 F2·B6. 이 순서가 돌 번호 0~3 이다. */
    public static final List<List<Cell>> FIXED_BENT_2P = List.of(
            List.of(new Cell(1, 1), new Cell(5, 5)),
            List.of(new Cell(5, 1), new Cell(1, 5)));

    private Setup() {
    }

    /** 한 사람이 가질 그 종류의 돌 수 — 2인은 2·2, 3인은 1·1. */
    public static int quota(int seatCount, StoneKind kind) {
        return switch (seatCount) {
            case 2 -> 2;
            case 3 -> 1;
            default -> throw new IllegalArgumentException("Dambaduk is 2~3 players: " + seatCount);
        };
    }

    /** 한 사람이 가질 돌 수 — 2인 4, 3인 2. */
    public static int stonesPerSeat(int seatCount) {
        return quota(seatCount, StoneKind.STRAIGHT) + quota(seatCount, StoneKind.BENT);
    }

    /**
     * 배치 순서(뱀 순서) — 선부터 좌석 +1. 2인 {@code f, f+1, f+1, f}(곧은돌 하나씩), 3인 {@code f, f+1, f+2, f+2, f+1, f}.
     */
    public static List<Integer> placementQueue(int seatCount, int firstSeat) {
        int f = firstSeat;
        int n = seatCount;
        return switch (seatCount) {
            case 2 -> List.of(f, (f + 1) % n, (f + 1) % n, f);
            case 3 -> List.of(f, (f + 1) % n, (f + 2) % n, (f + 2) % n, (f + 1) % n, f);
            default -> throw new IllegalArgumentException("Dambaduk is 2~3 players: " + seatCount);
        };
    }
}
