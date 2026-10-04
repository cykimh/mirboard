package com.mirboard.domain.game.onecard.state;

import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버 상태 → 클라 뷰 변환 (D-128). State Hiding(D-01)의 원카드 경계는 셋이다:
 * <ul>
 *   <li><b>손패</b> — 공개 뷰에는 좌석별 장수만, 카드는 본인 뷰에만</li>
 *   <li><b>뽑을 더미의 순서</b> — 장수만 공개</li>
 *   <li><b>봇 반응 시각</b> — 경쟁 창은 창 끝까지 남은 시간만 싣는다. 봇이 누를 시각
 *       ({@link RaceWindow#botPress()}, {@link RaceWindow#deadline()})은 어디에도 내보내지 않는다</li>
 * </ul>
 * 맨 위 카드·지정 무늘·공격 누적·방향·차례·탈락·결과는 공개 정보다.
 */
public final class OneCardStateMapper {

    private OneCardStateMapper() {
    }

    /** 공개 뷰 — 참가자·관전자 전원이 본다. */
    public record TableView(String phase,
                            List<SeatView> seats,
                            PlayingCard topCard,
                            Suit declaredSuit,
                            int attackStack,
                            int direction,
                            int turnSeat,
                            int drawPileCount,
                            RaceView race,
                            MatchResult result) {
    }

    /** @param eliminated 탈락했으면 사유, 살아 있으면 null */
    public record SeatView(int seat, int handCount, Elimination.Reason eliminated) {
    }

    /**
     * 열린 경쟁 창의 공개 부분.
     *
     * @param remainingMillis 창 끝까지 남은 시간(재접속한 클라가 버튼을 얼마나 보여 줄지). 봇이 누를
     *                        시각이 아니다 — 그건 서버만 안다
     */
    public record RaceView(int raceId, int ownerSeat, int slot, int jitterX, int jitterY,
                           long windowMillis, long remainingMillis) {
    }

    /** 본인 전용 — 손패 전체와 버전({@code HAND_UPDATED} 의 {@code handVersion} 과 같은 축). */
    public record PrivateView(int seat, List<PlayingCard> hand, int handVersion) {
    }

    /** @param now 지금 시각(epoch ms) — 경쟁 창의 남은 시간 계산용 */
    public static TableView toTableView(OneCardState state, long now) {
        List<SeatView> seats = new ArrayList<>();
        for (int seat = 0; seat < state.seatCount(); seat++) {
            seats.add(new SeatView(seat, state.hands().get(seat).size(), eliminationOf(state, seat)));
        }
        return new TableView(state.phaseName(), seats, state.topCard(), state.declaredSuit(),
                state.attackStack(), state.direction(), state.turnSeat(), state.drawPile().size(),
                raceView(state.race(), now), state.result());
    }

    public static PrivateView toPrivateView(OneCardState state, int seat) {
        if (seat < 0 || seat >= state.seatCount()) {
            throw new IllegalArgumentException("no such seat: " + seat);
        }
        return new PrivateView(seat, state.hands().get(seat), state.version());
    }

    private static RaceView raceView(RaceWindow race, long now) {
        if (race == null) {
            return null;
        }
        long remaining = Math.max(0L, race.openedAt() + race.windowMillis() - now);
        return new RaceView(race.raceId(), race.ownerSeat(), race.slot(), race.jitterX(), race.jitterY(),
                race.windowMillis(), remaining);
    }

    private static Elimination.Reason eliminationOf(OneCardState state, int seat) {
        for (Elimination e : state.eliminations()) {
            if (e.seat() == seat) {
                return e.reason();
            }
        }
        return null;
    }
}
