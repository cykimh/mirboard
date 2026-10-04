package com.mirboard.domain.game.onecard.state;

import java.util.List;

/**
 * 끝난 매치의 결과 (`docs/rules-onecard.md` §11). 1판 = 1매치라 라운드 누적이 없다.
 *
 * @param standings 좌석마다 한 줄, 순위 오름차순(같은 순위는 좌석 오름차순)
 */
public record MatchResult(EndReason reason, List<Standing> standings) {

    public MatchResult {
        standings = List.copyOf(standings);
    }

    /** 종료 사유 — §11.1 의 판정 순서와 같다. */
    public enum EndReason {
        FINISHED,
        LAST_STANDING,
        NO_HUMANS,
        STALEMATE
    }

    public enum SeatStatus {
        /** 마지막 카드를 냈다. */
        FINISHED,
        /** 끝까지 살아 있었다. */
        ALIVE,
        BANKRUPT,
        DESERTED
    }

    /**
     * @param rank      1부터. 동순위 다음은 건너뛴다(1, 1, 3)
     * @param cardsLeft 살아 있으면 남은 장수, 탈락했으면 탈락 순간의 장수
     */
    public record Standing(int seat, int rank, int cardsLeft, SeatStatus status) {
    }

    /** 승자 — 1등 전원(동순위 포함, §11.2). */
    public List<Integer> winners() {
        return standings.stream().filter(s -> s.rank() == 1).map(Standing::seat).toList();
    }
}
