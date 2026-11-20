package com.mirboard.domain.game.dambaduk.state;

import com.mirboard.domain.game.dambaduk.board.Dir;
import java.util.List;

/**
 * 차례인 사람이 고를 수 있는 옮김 하나 (룰 §4) — 돌·경로·도착 칸과, 그 도착에서 세울 수 있는 담 면들. 클라가 갈 수 있는 칸·담 자리를
 * 다시 계산하지 않게 서버가 보낸다. 목록 순서는 {@code stoneId} → 경로(1걸음 먼저, {@link Dir} 순) 이고 {@code walls} 도 {@link Dir} 순이다
 * — 시간 초과 자동 수가 이 순서의 첫 수다(룰 §8).
 *
 * @param jumpedFace 담 넘기로 없앨 면, 아니면 −1
 * @param walls      세울 수 있는 면(도착 칸 기준 방향) — 늘 하나 이상(룰 §4.3)
 */
public record MoveOption(int stoneId, List<Dir> path, int toX, int toY, boolean jump, int jumpedFace,
                         List<Dir> walls) {

    public MoveOption {
        path = List.copyOf(path);
        walls = List.copyOf(walls);
    }
}
