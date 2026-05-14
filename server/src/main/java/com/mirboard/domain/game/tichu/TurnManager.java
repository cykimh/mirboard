package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.tichu.state.PlayerState;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;

/**
 * 4인 좌석 모델 (0..3) 위의 차례 계산 유틸. 좌석은 시계방향으로 0→1→2→3→0.
 * 팀은 짝수 시트(0,2)=Team A, 홀수 시트(1,3)=Team B.
 */
public final class TurnManager {

    public static final int SEATS = 4;

    private TurnManager() {
    }

    public static int nextSeat(int seat) {
        return (seat + 1) % SEATS;
    }

    public static int partnerOf(int seat) {
        return (seat + 2) % SEATS;
    }

    /**
     * 다음 차례 시트를 계산한다. 카드 소진된 플레이어와 이번 트릭에서 이미 패스한
     * 플레이어는 건너뛴다. 모두 패스/완주 상태라 더 이상 따라갈 사람이 없으면
     * {@code currentTurnSeat} 와 같은 값을 반환 (트릭 종료 신호로 사용 가능).
     */
    public static int advanceTurn(TichuState.Playing state) {
        TrickState trick = state.trick();
        List<PlayerState> players = state.players();
        int start = trick.currentTurnSeat();
        int candidate = nextSeat(start);
        for (int i = 0; i < SEATS; i++) {
            if (candidate == trick.currentTopSeat()) {
                // 다시 currentTop 낸 사람 차례로 돌아왔다 → 모두가 패스/완주 → 트릭 종료
                return candidate;
            }
            PlayerState p = players.get(candidate);
            boolean finished = p.isFinished();
            boolean passed = trick.passedSeats().contains(candidate);
            if (!finished && !passed) {
                return candidate;
            }
            candidate = nextSeat(candidate);
        }
        return start;
    }
}
