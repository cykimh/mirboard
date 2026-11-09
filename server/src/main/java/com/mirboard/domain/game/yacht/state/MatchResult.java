package com.mirboard.domain.game.yacht.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 끝난 매치의 결과 (`docs/rules-yacht.md` §8). 1판 = 1매치.
 *
 * @param placements 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResult(EndReason reason, List<Placement> placements) {

    public MatchResult {
        placements = List.copyOf(placements);
    }

    /** 종료 사유 (§8.1). */
    public enum EndReason {
        /** 살아 있는 좌석 전원이 12칸을 채움. */
        FINISHED,
        /** 탈주로 살아 있는 사람이 없음. */
        NO_HUMANS,
        /** 성공한 액션 수가 상한에 닿음 — 무한 홀드 방지. */
        ACTION_LIMIT
    }

    /**
     * @param rank     1부터, 경쟁 순위(1, 1, 3). 탈주자는 모두 같은 최하위
     * @param total    총점(탈주자는 멈춘 총점)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Placement(int seat, int rank, int total, boolean deserted) {
    }

    /** 승자 — 1위 전원(동순위 포함, §8.2-4). */
    public List<Integer> winners() {
        return placements.stream().filter(p -> p.rank() == 1 && !p.deserted()).map(Placement::seat).toList();
    }
}
