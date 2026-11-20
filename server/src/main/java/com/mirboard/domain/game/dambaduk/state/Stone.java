package com.mirboard.domain.game.dambaduk.state;

import com.mirboard.domain.game.dambaduk.board.Cell;

/**
 * 판 위의 돌 하나 (룰 §1·§3).
 *
 * @param id 놓인 순서대로 0부터 — 판 안에서 고유하고 바뀌지 않는다(빠진 좌석의 돌을 치워도 남은 번호는 그대로)
 */
public record Stone(int id, int seat, StoneKind kind, int x, int y) {

    public Cell cell() {
        return new Cell(x, y);
    }

    public Stone movedTo(Cell c) {
        return new Stone(id, seat, kind, c.x(), c.y());
    }
}
