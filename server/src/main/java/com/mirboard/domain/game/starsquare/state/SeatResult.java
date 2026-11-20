package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 끝난 퍼즐 하나의 좌석 결과(`docs/rules-starsquare.md` §8.1).
 *
 * @param rank      퍼즐 순위(1 부터, 경쟁 순위 — 탈주자는 모두 같은 최하위)
 * @param points    좌석 수 − 순위, 탈주자 0
 * @param elapsedMs 완성까지 걸린 시간(ms), 못 했으면 −1
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatResult(int seat, int rank, int points, boolean solved, long elapsedMs, int filled, int lines,
                         boolean deserted) {
}
