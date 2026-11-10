package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 매치 결과 (§9.3). 순위는 1부터, 동순위는 같은 수(다음은 건너뜀).
 *
 * <p>팀전(D-225)이면 순위는 팀 단위다 — 이긴 팀 1위, 진 팀 그다음, 탈주자는 최하위. 무승부면 {@code winnerTeam} 이 null 이고
 * 탈주하지 않은 사람이 모두 1위다(승자 없음).
 *
 * @param teams      팀전 판인가(D-225 이전 JSON 에는 없다 — 개인전)
 * @param winnerTeam 팀전에서 이긴 팀(좌석 홀짝 0·1), 무승부·개인전이면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResult(EndReason reason, List<Placement> placements, boolean teams, Integer winnerTeam) {

    public MatchResult {
        placements = List.copyOf(placements);
    }

    /** 개인전 결과. */
    public MatchResult(EndReason reason, List<Placement> placements) {
        this(reason, placements, false, null);
    }

    public enum EndReason {
        LAST_STANDING, ALL_DOWN, NO_HUMANS
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Placement(int seat, int rank, int hp, boolean deserted) {
    }

    /** 이긴 좌석 — 개인전은 탈주하지 않은 1위(동순위면 모두), 팀전은 이긴 팀의 탈주하지 않은 사람, 무승부면 없음. */
    public List<Integer> winners() {
        if (teams) {
            if (winnerTeam == null) {
                return List.of();
            }
            return placements.stream().filter(p -> p.seat() % 2 == winnerTeam && !p.deserted())
                    .map(Placement::seat).sorted().toList();
        }
        return placements.stream().filter(p -> p.rank() == 1 && !p.deserted()).map(Placement::seat).toList();
    }

    /** 팀전 무승부인가 (D-225). */
    public boolean draw() {
        return teams && winnerTeam == null;
    }

    public int rankOf(int seat) {
        return placements.stream().filter(p -> p.seat() == seat).mapToInt(Placement::rank).findFirst().orElse(-1);
    }
}
