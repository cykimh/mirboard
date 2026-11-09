package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 매치 결과 (§9.3). 순위는 1부터, 동순위는 같은 수(다음은 건너뜀). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResult(EndReason reason, List<Placement> placements) {

    public MatchResult {
        placements = List.copyOf(placements);
    }

    public enum EndReason {
        LAST_STANDING, ALL_DOWN, NO_HUMANS
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Placement(int seat, int rank, int hp, boolean deserted) {
    }

    public List<Integer> winners() {
        return placements.stream().filter(p -> p.rank() == 1 && !p.deserted()).map(Placement::seat).toList();
    }

    public int rankOf(int seat) {
        return placements.stream().filter(p -> p.seat() == seat).mapToInt(Placement::rank).findFirst().orElse(-1);
    }
}
