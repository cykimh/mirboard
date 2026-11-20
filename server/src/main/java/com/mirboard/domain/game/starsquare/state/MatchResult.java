package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 끝난 매치의 결과(`docs/rules-starsquare.md` §8.2·§8.3).
 *
 * @param placements 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResult(EndReason reason, List<Placement> placements) {

    public MatchResult {
        placements = List.copyOf(placements);
    }

    public enum EndReason {
        /** R개 퍼즐을 다 함. */
        FINISHED,
        /** 탈주로 살아 있는 사람이 없음. */
        NO_HUMANS,
        /** 여럿 판에서 살아 있는 좌석이 하나만 남음. */
        LAST_SEAT
    }

    /**
     * @param rank   1 부터, 경쟁 순위(1, 1, 3). 탈주자는 모두 같은 최하위
     * @param points 퍼즐 점수 합
     * @param firsts 퍼즐 1등 횟수
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Placement(int seat, int rank, int points, int firsts, boolean deserted) {
    }

    /** 승자 — 1위 전원(동순위 포함, 탈주자 제외). */
    public List<Integer> winners() {
        return placements.stream().filter(p -> p.rank() == 1 && !p.deserted()).map(Placement::seat).toList();
    }
}
