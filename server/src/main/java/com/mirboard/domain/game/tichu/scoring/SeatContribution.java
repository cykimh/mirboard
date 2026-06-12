package com.mirboard.domain.game.tichu.scoring;

/**
 * 한 좌석의 매치 누적 기여도 — MVP 산정 입력. 라운드마다 합산된다.
 *
 * @param seat          좌석 0..3
 * @param trickPoints   가져간 트릭 카드 점수 누적
 * @param tichuBonus    티츄/그랜드 선언 보너스 누적(성공 +, 실패 −)
 * @param firstFinishes 라운드 1등(첫 완주) 횟수
 * @param orderPoints   완주 순위 가점 누적(1등 3 · 2등 2 · 3등 1 · 그 외 0)
 */
public record SeatContribution(
        int seat,
        int trickPoints,
        int tichuBonus,
        int firstFinishes,
        int orderPoints) {

    public static SeatContribution zero(int seat) {
        return new SeatContribution(seat, 0, 0, 0, 0);
    }

    public SeatContribution plus(SeatContribution o) {
        return new SeatContribution(
                seat,
                trickPoints + o.trickPoints,
                tichuBonus + o.tichuBonus,
                firstFinishes + o.firstFinishes,
                orderPoints + o.orderPoints);
    }

    /** MVP 가중 점수 — 트릭 + 선언보너스 + 1등(×40) + 완주순위(×10). */
    public int weightedScore() {
        return trickPoints + tichuBonus + firstFinishes * 40 + orderPoints * 10;
    }
}
