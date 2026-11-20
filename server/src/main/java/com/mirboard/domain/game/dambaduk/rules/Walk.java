package com.mirboard.domain.game.dambaduk.rules;

import com.mirboard.domain.game.dambaduk.board.Cell;
import java.util.List;

/**
 * 경로를 판 위에서 걸어 본 결과 (룰 §4).
 *
 * @param blocked 판 밖·바깥담으로 나가거나 돌이 있는 칸을 지나거나 도착함
 * @param to      도착 칸(막혔으면 의미 없음)
 * @param crossed 지난 담의 면 번호(걸음 순)
 */
public record Walk(boolean blocked, Cell to, List<Integer> crossed) {

    public Walk {
        crossed = List.copyOf(crossed);
    }
}
