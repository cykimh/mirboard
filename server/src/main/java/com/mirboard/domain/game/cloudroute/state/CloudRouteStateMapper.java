package com.mirboard.domain.game.cloudroute.state;

import com.mirboard.domain.game.cloudroute.board.Letter;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버 상태 → 클라 뷰 변환 (D-212). 구름항로의 State Hiding 경계는 <b>편지 더미 순서 하나</b>다(룰 §13) — 공개 뷰에는 남은
 * 장수만 싣는다. 현금·위치·땅·건물·축제·부표·버린 더미·기다리는 결정은 모두 테이블 공개 정보라 그대로 싣고, 본인 전용 뷰는 없다.
 */
public final class CloudRouteStateMapper {

    private CloudRouteStateMapper() {
    }

    /**
     * 공개 뷰 — 참가자·관전자 전원이 본다.
     *
     * @param pending 차례 좌석이 기다리는 결정(결정 패널용), 끝났으면 null
     * @param deckCount 편지 더미 남은 장수 — 순서는 싣지 않는다
     */
    public record TableView(String phase,
                            List<SeatView> seats,
                            List<TileView> tiles,
                            int festivalTile,
                            int round,
                            int roundLimit,
                            int turnSeat,
                            int doublesThisTurn,
                            Pending pending,
                            List<Integer> lastDice,
                            int deckCount,
                            List<Letter> discard,
                            MatchResult result) {
    }

    /**
     * @param eliminated  탈락했으면 사유, 살아 있으면 null
     * @param totalAssets 현금 + 가진 땅의 정가(§12.2) — 좌석 줄·결과 화면용
     */
    public record SeatView(int seat, int cash, int position, boolean trapped, int failedEscapes, boolean holdsBuoy,
                           Elimination.Reason eliminated, int totalAssets) {
    }

    /** 칸 하나 — 주인 없으면 owner −1. */
    public record TileView(int tile, int owner, int level) {
    }

    public static TableView toTableView(CloudRouteState state) {
        List<SeatView> seats = new ArrayList<>();
        for (int seat = 0; seat < state.seatCount(); seat++) {
            SeatState s = state.seat(seat);
            seats.add(new SeatView(seat, s.cash(), s.position(), s.trapped(), s.failedEscapes(),
                    state.buoyHolder() == seat, eliminationOf(state, seat), state.totalAssets(seat)));
        }
        List<TileView> tiles = new ArrayList<>();
        for (int tile = 0; tile < state.owners().size(); tile++) {
            tiles.add(new TileView(tile, state.owners().get(tile), state.levels().get(tile)));
        }
        return new TableView(state.phaseName(), seats, tiles, state.festivalTile(), state.round(), state.roundLimit(),
                state.turnSeat(), state.doublesThisTurn(), state.pending(), state.lastDice(), state.letterDeck().size(),
                state.letterDiscard(), state.result());
    }

    private static Elimination.Reason eliminationOf(CloudRouteState state, int seat) {
        for (Elimination e : state.eliminations()) {
            if (e.seat() == seat) {
                return e.reason();
            }
        }
        return null;
    }
}
