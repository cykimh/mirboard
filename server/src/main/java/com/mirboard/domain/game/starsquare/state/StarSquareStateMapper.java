package com.mirboard.domain.game.starsquare.state;

import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 상태 → 뷰(`docs/rules-starsquare.md` §11). <b>공개 뷰 타입에는 정답·푸는 중인 남의 판 필드가 없다</b> — 타입을 나눠 은닉한다
 * (State Hiding, D-01). 끝난 퍼즐의 판·정답만 {@link PuzzleOutcome} 으로 실린다. 본인 판은 {@link PrivateBoard} 로 따로.
 */
public final class StarSquareStateMapper {

    private StarSquareStateMapper() {
    }

    /**
     * @param solvePlace 완성 순서(1 부터), 아직이면 0
     * @param elapsedMs  완성까지 걸린 시간, 아직이면 −1
     * @param points     점수 합(끝난 퍼즐들)
     * @param firsts     퍼즐 1등 횟수
     */
    public record SeatView(int seat, int filled, int lines, boolean solved, int solvePlace, long elapsedMs,
                           int points, int firsts, boolean deserted) {
    }

    /**
     * 공개 뷰. 준비 단계는 퍼즐을 숨긴다(크기·M 0, 단서 null, 진행도 0 — §6.1).
     *
     * @param remainingMs 지금 단계가 끝날 때까지(엔진 계산 — 준비 끝·마감·결과 보기 끝)
     * @param closing     첫 완성으로 마감이 당겨졌나
     * @param lastPuzzle  결과 보기·끝난 매치면 방금 끝난 퍼즐(모두의 판·정답), 그 밖에는 null(푸는 도중 탈주로 끝난 매치 포함)
     * @param result      끝났으면 매치 결과
     */
    public record PublicView(String phase, int puzzleIndex, int puzzleCount, int size, int magicSum, Grid clues,
                             long remainingMs, boolean closing, List<SeatView> seats, PuzzleOutcome lastPuzzle,
                             MatchResult result) {
    }

    /** 본인 판(비공개 뷰) — 재접속·다른 탭에서 이어 풀기용. */
    public record PrivateBoard(int puzzleIndex, Grid grid, long version, boolean solved) {
    }

    public static PublicView toPublicView(StarSquareState state, long remainingMs) {
        boolean intro = state.phase() == Phase.INTRO;
        List<Integer> totals = state.totals();
        List<Integer> firsts = state.firsts();
        List<SeatView> seats = new ArrayList<>(state.seatCount());
        for (int s = 0; s < state.seatCount(); s++) {
            SeatBoard b = state.board(s);
            seats.add(intro
                    ? new SeatView(s, 0, 0, false, 0, -1, totals.get(s), firsts.get(s), !state.alive(s))
                    : new SeatView(s, b.filled(), b.lines(), b.solved(), b.solvePlace(), b.elapsedMs(), totals.get(s),
                            firsts.get(s), !state.alive(s)));
        }
        int n = intro ? 0 : state.puzzle().n();
        // 방금 끝난 퍼즐만 — 푸는 도중 탈주로 매치가 끝났으면 그 퍼즐은 결과가 없으므로 지난 퍼즐을 다시 보이지 않는다
        PuzzleOutcome newest = state.outcomes().isEmpty() ? null : state.outcomes().get(state.outcomes().size() - 1);
        PuzzleOutcome last = (state.phase() == Phase.RESULT || state.phase() == Phase.ENDED)
                && newest != null && newest.index() == state.index()
                ? newest
                : null;
        return new PublicView(state.phase().name(), state.index(), state.puzzleCount(), n,
                intro ? 0 : MagicCheck.magicSum(n), intro ? null : state.puzzle().clues(), remainingMs,
                state.phase() == Phase.PLAYING && state.firstSolvedAt() >= 0, List.copyOf(seats), last,
                state.result());
    }

    /** 본인 판. 준비 단계(퍼즐 숨김)·봇·범위 밖 좌석은 없다. */
    public static Optional<PrivateBoard> toPrivateBoard(StarSquareState state, int seat, Set<Integer> botSeats) {
        if (seat < 0 || seat >= state.seatCount() || botSeats.contains(seat) || state.phase() == Phase.INTRO) {
            return Optional.empty();
        }
        SeatBoard b = state.board(seat);
        return Optional.of(new PrivateBoard(state.index(), b.grid(), b.version(), b.solved()));
    }
}
