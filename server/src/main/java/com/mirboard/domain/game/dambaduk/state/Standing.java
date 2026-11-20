package com.mirboard.domain.game.dambaduk.state;

/**
 * 끝난 판의 좌석 하나 (룰 §7).
 *
 * @param rank 1부터, 동순위는 같은 수(1·1·3). 빠진 사람은 남은 사람 모두보다 아래, 서로 동순위
 * @param exit 빠졌으면 그 까닭, 아니면 null
 */
public record Standing(int seat, int score, int largest, int rank, ExitReason exit) {
}
