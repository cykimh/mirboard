package com.mirboard.domain.game.dambaduk.state;

import java.util.List;

/**
 * 터 하나 (룰 §1·§5) — 서버가 계산해 공개 뷰·이벤트에 싣는다(클라는 다시 계산하지 않는다).
 *
 * @param cells 칸 번호({@code y·7 + x}) 오름차순
 * @param owner 집이면 그 좌석, 아니면 −1
 * @param seats 이 터에 돌이 있는 좌석들 오름차순
 */
public record Region(List<Integer> cells, RegionKind kind, int owner, List<Integer> seats) {

    public Region {
        cells = List.copyOf(cells);
        seats = List.copyOf(seats);
    }

    public int size() {
        return cells.size();
    }
}
