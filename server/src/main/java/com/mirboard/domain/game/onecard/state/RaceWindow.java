package com.mirboard.domain.game.onecard.state;

/**
 * 열린 외치기 경쟁 창 (`docs/rules-onecard.md` §9).
 *
 * @param raceId       창 번호 — 이 번호가 다른 누름은 거절한다(§9-5)
 * @param ownerSeat    1장 남은 사람
 * @param slot         버튼 위치 슬롯(0..slotCount-1). 전원에게 같은 위치다
 * @param jitterX      슬롯 안 가로 흔들림, −100~100 (클라가 슬롯 반경으로 환산)
 * @param jitterY      세로 흔들림, −100~100
 * @param openedAt     창을 연 시각(epoch ms)
 * @param windowMillis 창 길이
 * @param nextSeat     창이 닫히면 차례를 받을 좌석 — 카드를 낸 순간 정해 둔다(§9.2)
 * @param botPress     창을 열 때 추첨한 가장 빠른 봇의 누름. 봇이 없거나 창보다 늦으면 null.
 *                     <b>서버 전용</b> — 공개 뷰·이벤트에 싣지 않는다(설계서 §4.9)
 */
public record RaceWindow(int raceId,
                         int ownerSeat,
                         int slot,
                         int jitterX,
                         int jitterY,
                         long openedAt,
                         long windowMillis,
                         int nextSeat,
                         BotPress botPress) {

    /**
     * 봇 한 명의 누름.
     *
     * @param call        true 면 주인의 "원카드!", false 면 다른 봇의 "잡기!"
     * @param delayMillis 창이 열린 뒤 누르기까지의 반응 시간
     */
    public record BotPress(int seat, boolean call, long delayMillis) {
    }

    /** 창이 저절로 닫히는 시각 — 가장 빠른 봇의 누름 또는 창 길이 중 이른 쪽. */
    public long deadline() {
        return openedAt + (botPress != null ? botPress.delayMillis() : windowMillis);
    }
}
