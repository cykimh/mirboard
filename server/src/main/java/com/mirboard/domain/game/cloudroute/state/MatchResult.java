package com.mirboard.domain.game.cloudroute.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 끝난 매치의 결과 (`docs/rules-cloudroute.md` §12). 1판 = 1매치.
 *
 * @param standings 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchResult(EndReason reason, int rounds, List<Standing> standings) {

    public MatchResult {
        standings = List.copyOf(standings);
    }

    /** 종료 사유 — §12.1 의 판정 순서와 같다. */
    public enum EndReason {
        LAST_STANDING,
        NO_HUMANS,
        ROUND_LIMIT
    }

    public enum SeatStatus {
        ALIVE,
        BANKRUPT,
        DESERTED
    }

    /**
     * @param rank        1부터. 동순위 다음은 건너뛴다(1, 1, 3)
     * @param totalAssets 현금 + 땅값 + 건설비(정가). 탈락자는 0
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Standing(int seat, int rank, int totalAssets, int cash, SeatStatus status) {
    }

    /** 승자 — 1등 전원(동순위 포함). */
    public List<Integer> winners() {
        return standings.stream().filter(s -> s.rank() == 1).map(Standing::seat).toList();
    }
}
