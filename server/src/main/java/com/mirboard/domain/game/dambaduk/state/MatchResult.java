package com.mirboard.domain.game.dambaduk.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import java.util.List;

/**
 * 판 결과 (룰 §6·§7).
 *
 * @param standings 좌석 순
 * @param winners   1위(공동 포함) 좌석 오름차순 — 무승부면 빈 목록
 * @param draw      빠진 사람이 없고 모두 동순위
 * @param causeSeat 2인 시간패·탈주를 만든 좌석, 그 밖은 −1
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
public record MatchResult(EndReason reason, List<Standing> standings, List<Integer> winners, boolean draw,
                          int causeSeat) {

    public MatchResult {
        standings = standings == null ? List.of() : List.copyOf(standings);
        winners = winners == null ? List.of() : List.copyOf(winners);
    }
}
