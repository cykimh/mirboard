package com.mirboard.domain.game.artillery.state;

/**
 * 차례 규칙 (D-226). {@link #DELAY} 가 지금 규칙이다 — 누적 딜레이가 가장 작은 살아 있는 포대가 다음 차례(포트리스식).
 * {@link #SEAT} 는 D-226 이전 규칙(살아 있는 다음 좌석)으로, 그 전에 시작해 Redis 에 남은 판(JSON 에 이 필드가 없음)과 좌석 순서 골든
 * 테스트가 쓴다.
 */
public enum TurnOrder {
    SEAT, DELAY
}
