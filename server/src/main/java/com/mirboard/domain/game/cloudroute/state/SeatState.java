package com.mirboard.domain.game.cloudroute.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 좌석 하나 (`docs/rules-cloudroute.md` §3·§7). 탈락자는 현금 0·소용돌이 아님으로 남는다.
 *
 * @param trapped        소용돌이에 갇혔는가
 * @param failedEscapes  갇힌 뒤 굴려서 못 나온 횟수(0~2, §7.2)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatState(int cash, int position, boolean trapped, int failedEscapes) {
}
