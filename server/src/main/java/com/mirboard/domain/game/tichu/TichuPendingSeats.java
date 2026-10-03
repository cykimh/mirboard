package com.mirboard.domain.game.tichu;

import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.state.TichuState;
import com.mirboard.domain.game.tichu.state.TrickState;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * 지금 행동을 기다리는 좌석 계산 (D-118 에서 {@link TichuGameEngine} 밖으로 옮김).
 *
 * <p>포트 어댑터의 {@code pendingSeats} 와 봇 시뮬레이션 하네스가 <b>같은 코드</b>를 써야
 * 하네스에서 통과한 매치가 운영 스케줄러에서도 같은 순서로 흘러간다. 동작은 옮기기 전과
 * 같다 — 계약은 {@code TichuGameEnginePendingSeatsTest} 가 고정한다.
 *
 * <ul>
 *   <li>Dealing — 아직 ready 가 아닌 좌석 전부 (동시 대기)</li>
 *   <li>Passing — 아직 제출하지 않은 좌석 전부 (동시 대기)</li>
 *   <li>Playing — 현재 차례 한 좌석. 용 트릭 양도가 보류 중이면 트릭을 가져간 좌석</li>
 *   <li>RoundEnd — 없음</li>
 * </ul>
 */
public final class TichuPendingSeats {

    private TichuPendingSeats() {
    }

    public static List<Integer> of(TichuState state) {
        return switch (state) {
            case TichuState.Dealing d -> seatsWhere(d.players().size(), s -> !d.ready().contains(s));
            case TichuState.Passing p ->
                    seatsWhere(p.players().size(), s -> !p.submitted().containsKey(s));
            case TichuState.Playing pl -> playingPendingSeats(pl);
            case TichuState.RoundEnd __ -> List.of();
        };
    }

    private static List<Integer> playingPendingSeats(TichuState.Playing playing) {
        TrickState trick = playing.trick();
        // 용으로 트릭을 가져간 좌석은 양도(GiveDragonTrick)를 마칠 때까지 차례를 붙잡는다.
        if (dragonGivePending(trick)) {
            return List.of(trick.currentTopSeat());
        }
        int current = trick.currentTurnSeat();
        if (current < 0 || playing.players().get(current).isFinished()) {
            return List.of();
        }
        return List.of(current);
    }

    private static boolean dragonGivePending(TrickState trick) {
        return trick.currentTop() != null
                && trick.currentTop().cards().size() == 1
                && trick.currentTop().cards().get(0).is(Special.DRAGON);
    }

    private static List<Integer> seatsWhere(int seatCount, IntPredicate pending) {
        return IntStream.range(0, seatCount).filter(pending).boxed().toList();
    }
}
