package com.mirboard.domain.game.starsquare.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 별자리 수판 한 판의 전체 상태(`docs/rules-starsquare.md`). 1판 = 1매치, 퍼즐 R개는 이 안의 목록이다.
 *
 * <p><b>서버만 본다.</b> 정답({@link PuzzleSpec#solution()})과 각자의 판이 들어 있다 — 공개 뷰는 {@code StarSquareStateMapper} 가 따로
 * 만들고(정답·남의 판 필드 없음), 본인 판은 비공개 뷰로만 나간다(§11). 봇 진행용 매치 시드도 여기 있다(봇은 예측돼도 상관없다 — §10).
 *
 * <p>Redis 에 JSON 으로 저장된다(S2). 모르는 필드는 무시한다.
 *
 * @param puzzles        매치 시작 때 고른 퍼즐 R개(대칭 변환까지 입힌 것)
 * @param index          지금 퍼즐 번호(0 부터) — 준비 단계는 0, 결과 보기는 방금 끝난 퍼즐
 * @param phase          단계
 * @param phaseStartedAt 지금 단계가 시작된 시각(ms) — 준비 시작·퍼즐 시작·결과 보기 시작
 * @param firstSolvedAt  지금 퍼즐의 첫 완성 시각(ms), 없으면 −1 — 마감 당김의 기준(§6.2-5)
 * @param boards         좌석별 지금 퍼즐 판(좌석 수만큼)
 * @param deserted       탈주한 좌석(오름차순)
 * @param outcomes       끝난 퍼즐의 결과(순서대로)
 * @param matchSeed      봇 페이스의 시드(§10)
 * @param result         끝났으면 결과, 아니면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StarSquareState(List<PuzzleSpec> puzzles,
                              int index,
                              Phase phase,
                              long phaseStartedAt,
                              long firstSolvedAt,
                              List<SeatBoard> boards,
                              List<Integer> deserted,
                              List<PuzzleOutcome> outcomes,
                              long matchSeed,
                              MatchResult result) implements GameState {

    public StarSquareState {
        puzzles = List.copyOf(puzzles);
        boards = List.copyOf(boards);
        deserted = deserted == null ? List.of() : deserted.stream().sorted().distinct().toList();
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public int seatCount() {
        return boards.size();
    }

    public int puzzleCount() {
        return puzzles.size();
    }

    public PuzzleSpec puzzle() {
        return puzzles.get(index);
    }

    public SeatBoard board(int seat) {
        return boards.get(seat);
    }

    public boolean alive(int seat) {
        return !deserted.contains(seat);
    }

    public List<Integer> aliveSeats() {
        return IntStream.range(0, seatCount()).filter(this::alive).boxed().toList();
    }

    public boolean ended() {
        return phase == Phase.ENDED;
    }

    /** 좌석별 점수 합(끝난 퍼즐들). */
    public List<Integer> totals() {
        int[] t = new int[seatCount()];
        for (PuzzleOutcome o : outcomes) {
            for (SeatResult r : o.results()) {
                t[r.seat()] += r.points();
            }
        }
        return IntStream.of(t).boxed().toList();
    }

    /** 좌석별 퍼즐 1등 횟수(탈주한 퍼즐은 세지 않음). */
    public List<Integer> firsts() {
        int[] f = new int[seatCount()];
        for (PuzzleOutcome o : outcomes) {
            for (SeatResult r : o.results()) {
                if (r.rank() == 1 && !r.deserted()) {
                    f[r.seat()]++;
                }
            }
        }
        return IntStream.of(f).boxed().toList();
    }

    public StarSquareState withBoard(int seat, SeatBoard board) {
        List<SeatBoard> next = new ArrayList<>(boards);
        next.set(seat, board);
        return new StarSquareState(puzzles, index, phase, phaseStartedAt, firstSolvedAt, next, deserted, outcomes,
                matchSeed, result);
    }

    public StarSquareState withFirstSolvedAt(long at) {
        return new StarSquareState(puzzles, index, phase, phaseStartedAt, at, boards, deserted, outcomes, matchSeed,
                result);
    }

    public StarSquareState withDeserted(List<Integer> next) {
        return new StarSquareState(puzzles, index, phase, phaseStartedAt, firstSolvedAt, boards, next, outcomes,
                matchSeed, result);
    }

    /**
     * {@code viewer} 가 볼 수 있는 남의 판의 좌석(§11.2, D-294) — 푸는 단계에서 살아 있고 이 퍼즐을 <b>완성한</b> 좌석만 본다. 대상은 살아 있고
     * 아직 완성하지 않은 다른 좌석(오름차순). 그 밖에는 빈 목록 — 아직 푸는 좌석·탈주한 좌석·범위 밖(관전자)은 아무것도 못 본다.
     * 봇 좌석을 거르는 것은 부르는 쪽이다(상태는 봇 좌석을 모른다).
     */
    public List<Integer> peerSeats(int viewer) {
        if (phase != Phase.PLAYING || viewer < 0 || viewer >= seatCount() || !alive(viewer)
                || !board(viewer).solved()) {
            return List.of();
        }
        return aliveSeats().stream().filter(s -> s != viewer && !board(s).solved()).toList();
    }

    /** 완성한 좌석 수(지금 퍼즐, 탈주 포함). */
    public int solvedCount() {
        return (int) boards.stream().filter(SeatBoard::solved).count();
    }
}
