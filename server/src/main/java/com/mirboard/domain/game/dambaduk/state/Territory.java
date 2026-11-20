package com.mirboard.domain.game.dambaduk.state;

import java.util.List;

/**
 * 지금 판의 터와 좌석별 점수 (룰 §5). "지금 그대로 끝나면"의 값이다 — 확정이 아니다.
 *
 * @param regions 터 목록 — 가장 작은 칸 번호 순
 * @param scores  좌석별 집 칸 수 합
 * @param largest 좌석별 가장 큰 집의 칸 수(집이 없으면 0)
 */
public record Territory(List<Region> regions, List<Integer> scores, List<Integer> largest) {

    public Territory {
        regions = List.copyOf(regions);
        scores = List.copyOf(scores);
        largest = List.copyOf(largest);
    }

    public boolean hasContested() {
        return regions.stream().anyMatch(r -> r.kind() == RegionKind.CONTESTED);
    }
}
