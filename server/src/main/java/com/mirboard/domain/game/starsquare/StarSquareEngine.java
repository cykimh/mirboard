package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.starsquare.action.RejectionReason;
import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.action.StarSquareActionRejectedException;
import com.mirboard.domain.game.starsquare.bot.BotPace;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.puzzle.MagicCheck;
import com.mirboard.domain.game.starsquare.puzzle.Progress;
import com.mirboard.domain.game.starsquare.puzzle.PuzzleSpec;
import com.mirboard.domain.game.starsquare.rules.MatchRanking;
import com.mirboard.domain.game.starsquare.rules.PuzzleRanking;
import com.mirboard.domain.game.starsquare.state.BotPlan;
import com.mirboard.domain.game.starsquare.state.MatchResult;
import com.mirboard.domain.game.starsquare.state.Phase;
import com.mirboard.domain.game.starsquare.state.PuzzleOutcome;
import com.mirboard.domain.game.starsquare.state.SeatBoard;
import com.mirboard.domain.game.starsquare.state.SeatResult;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

/**
 * 별자리 수판 순수 룰 엔진(`docs/rules-starsquare.md`). 저장소·시계·난수기가 없다 — 시각은 인자로 받고, 퍼즐은 시작 때 골라서 받는다.
 * 포트 어댑터(S2)가 이것을 감싸 저장·로컬 발행·엔진 타이머를 붙인다(D-98 2계층).
 *
 * <p>모든 좌석이 같은 퍼즐을 <b>동시에</b> 푼다 — 차례가 없다. 시간이 지나면 일어나는 것(준비 끝·봇 이정표·마감·결과 보기 끝)은
 * 전부 {@link #onTimer} 하나이고, {@link #timer} 가 그 가장 이른 시각까지 남은 시간을 돌려준다(포트 ⑦ — 시작 시각을 상태에 두므로
 * 재무장해도 처음부터 세지 않는다). 거절은 {@link StarSquareActionRejectedException} 이고 상태를 바꾸지 않는다.
 */
public final class StarSquareEngine {

    /** 시작 준비(§6.1) [결정] — 운영값. 실제로는 {@link StarSquareConfig#introMillis()}(배율). */
    public static final long INTRO_MS = 3_000L;
    /** 첫 완성 뒤 마감(§6.3 ②) [수치]. */
    public static final long CLOSE_AFTER_FIRST_MS = 30_000L;
    /** 퍼즐 사이 결과 보기(§6.4) [수치]. */
    public static final long RESULT_MS = 8_000L;

    private final StarSquareConfig config;

    public StarSquareEngine(StarSquareConfig config) {
        this.config = config;
    }

    public StarSquareConfig config() {
        return config;
    }

    public record Result(StarSquareState newState, List<StarSquareEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    public record Desertion(StarSquareState newState, Outcome outcome, List<StarSquareEvent> events) {

        public enum Outcome {
            /** 이미 탈주했거나 끝난 매치 — 아무것도 바뀌지 않았다. */
            NOT_APPLICABLE,
            MATCH_CONTINUES,
            MATCH_ENDED
        }

        public Desertion {
            events = List.copyOf(events);
        }
    }

    // ---- 시작 (§3·§6.1) ----

    /**
     * 준비 단계로 시작한다. 퍼즐 R개는 부르는 쪽이 골라 준다({@code PuzzlePicker}). 시작 때 이벤트는 없다 — 시작 상태는 resync 로,
     * 첫 퍼즐은 준비가 끝난 타이머의 {@code PUZZLE_STARTED} 로 간다.
     */
    public Result start(List<PuzzleSpec> puzzles, long matchSeed, long now) {
        if (puzzles == null || puzzles.size() != config.puzzleCount()) {
            throw new IllegalArgumentException("Expected " + config.puzzleCount() + " puzzles");
        }
        Grid clues = puzzles.get(0).clues();
        List<SeatBoard> boards = new ArrayList<>(Collections.nCopies(config.seatCount(), SeatBoard.fresh(clues, null)));
        StarSquareState state = new StarSquareState(puzzles, 0, Phase.INTRO, now, -1, boards, List.of(), List.of(),
                matchSeed, null);
        return new Result(state, List.of());
    }

    // ---- 액션 (§7) ----

    public Result apply(StarSquareState state, int seat, StarSquareAction action, long now) {
        if (state.ended()) {
            throw reject(RejectionReason.MATCH_OVER);
        }
        if (action == null) {
            throw reject(RejectionReason.INVALID_ACTION);
        }
        return switch (action) {
            case StarSquareAction.Sync sync -> sync(state, seat, sync, now);
        };
    }

    private Result sync(StarSquareState state, int seat, StarSquareAction.Sync sync, long now) {
        if (seat < 0 || seat >= state.seatCount() || !state.alive(seat) || config.bot(seat)) {
            throw reject(RejectionReason.NOT_IN_PLAY);
        }
        if (state.phase() != Phase.PLAYING || now >= deadline(state)) {
            throw reject(RejectionReason.PUZZLE_CLOSED);
        }
        if (sync.puzzle() < state.index()) {
            throw reject(RejectionReason.STALE_SYNC);
        }
        if (sync.puzzle() > state.index()) {
            throw reject(RejectionReason.BAD_GRID);
        }
        SeatBoard board = state.board(seat);
        if (board.solved()) {
            throw reject(RejectionReason.ALREADY_SOLVED);
        }
        PuzzleSpec puzzle = state.puzzle();
        Grid grid = parse(sync.grid(), puzzle.n());
        if (!puzzle.clues().nonZeroCellsMatch(grid)) {
            throw reject(RejectionReason.CLUE_CHANGED);
        }
        if (sync.version() < board.version()
                || (sync.version() == board.version() && !grid.equals(board.grid()))) {
            throw reject(RejectionReason.STALE_SYNC);
        }
        if (sync.version() == board.version()) {
            return new Result(state, List.of()); // 같은 프레임의 재전송·인프라 폴백 — 받되 무변화(§7-3)
        }
        SeatBoard next = board.withGrid(grid, sync.version());
        StarSquareState updated = state.withBoard(seat, next);
        List<StarSquareEvent> events = new ArrayList<>(3);
        if (MagicCheck.isMagic(grid, puzzle.clues())) {
            updated = markSolved(updated, seat, now - state.phaseStartedAt(), now, events);
        } else if (!next.progress().equals(board.progress())) {
            events.add(new StarSquareEvent.Progress(seat, next.filled(), next.lines()));
        }
        return new Result(updated, events);
    }

    private static Grid parse(List<Integer> values, int n) {
        int nn = n * n;
        if (values == null || values.size() != nn) {
            throw reject(RejectionReason.BAD_GRID);
        }
        int[] cells = new int[nn];
        for (int i = 0; i < nn; i++) {
            Integer v = values.get(i);
            if (v == null || v < 0 || v > nn) {
                throw reject(RejectionReason.BAD_GRID);
            }
            cells[i] = v;
        }
        return Grid.of(cells);
    }

    // ---- 완성·퍼즐 끝·매치 끝 (§6·§8) ----

    /** 좌석 완성 처리. 모두 완성이면 퍼즐을 끝내고, 첫 완성이면 마감을 당긴다. */
    private StarSquareState markSolved(StarSquareState state, int seat, long elapsed, long now,
                                       List<StarSquareEvent> events) {
        int place = state.solvedCount() + 1;
        StarSquareState solved = state.withBoard(seat, state.board(seat).solvedAs(place, elapsed));
        events.add(new StarSquareEvent.Solved(seat, place, elapsed));
        boolean first = solved.firstSolvedAt() < 0;
        if (first) {
            solved = solved.withFirstSolvedAt(solved.phaseStartedAt() + elapsed);
        }
        if (allAliveSolved(solved)) {
            return endPuzzle(solved, PuzzleOutcome.EndReason.ALL_SOLVED, now, events);
        }
        if (first) {
            events.add(new StarSquareEvent.Closing(Math.max(0, deadline(solved) - now)));
        }
        return solved;
    }

    private static boolean allAliveSolved(StarSquareState state) {
        List<Integer> alive = state.aliveSeats();
        return !alive.isEmpty() && alive.stream().allMatch(s -> state.board(s).solved());
    }

    private StarSquareState endPuzzle(StarSquareState state, PuzzleOutcome.EndReason reason, long now,
                                      List<StarSquareEvent> events) {
        List<SeatResult> results = PuzzleRanking.rank(state);
        List<Grid> grids = state.boards().stream().map(SeatBoard::grid).toList();
        PuzzleOutcome outcome = new PuzzleOutcome(state.index(), reason, results, grids, state.puzzle().solution());
        List<PuzzleOutcome> outcomes = new ArrayList<>(state.outcomes());
        outcomes.add(outcome);
        StarSquareState done = new StarSquareState(state.puzzles(), state.index(), Phase.RESULT, now,
                state.firstSolvedAt(), state.boards(), state.deserted(), outcomes, state.matchSeed(), null);
        boolean last = state.index() == state.puzzleCount() - 1;
        events.add(new StarSquareEvent.PuzzleEnded(state.index(), reason, results, grids, state.puzzle().solution(),
                done.totals(), last ? 0 : config.resultMillis()));
        return last ? finishMatch(done, MatchResult.EndReason.FINISHED, now, events) : done;
    }

    private StarSquareState finishMatch(StarSquareState state, MatchResult.EndReason reason, long now,
                                        List<StarSquareEvent> events) {
        MatchResult result = MatchRanking.result(state, reason);
        events.add(new StarSquareEvent.MatchEnded(reason, result.placements()));
        return new StarSquareState(state.puzzles(), state.index(), Phase.ENDED, now, state.firstSolvedAt(),
                state.boards(), state.deserted(), state.outcomes(), state.matchSeed(), result);
    }

    private StarSquareState startPuzzle(StarSquareState state, int index, long now, List<StarSquareEvent> events) {
        PuzzleSpec spec = state.puzzles().get(index);
        List<SeatBoard> boards = new ArrayList<>(state.seatCount());
        for (int seat = 0; seat < state.seatCount(); seat++) {
            BotPlan pace = state.alive(seat) && config.bot(seat)
                    ? BotPace.plan(state.matchSeed(), index, seat, spec.band(), config.botLevel(), spec.clues(),
                            config.timeScale())
                    : null;
            boards.add(SeatBoard.fresh(spec.clues(), pace));
        }
        StarSquareState started = new StarSquareState(state.puzzles(), index, Phase.PLAYING, now, -1, boards,
                state.deserted(), state.outcomes(), state.matchSeed(), null);
        Progress p = MagicCheck.progress(spec.clues());
        events.add(new StarSquareEvent.PuzzleStarted(index, state.puzzleCount(), spec.n(),
                MagicCheck.magicSum(spec.n()), spec.clues(), config.limitMillis(), p.filled(), p.lines()));
        return started;
    }

    // ---- 시간 (§9) ----

    /** 지금 퍼즐의 마감 시각 — 퍼즐 시작 + 제한, 첫 완성이 있으면 그 + 30초와 더 이른 쪽. 푸는 단계에서만 뜻이 있다. */
    public long deadline(StarSquareState state) {
        long limit = state.phaseStartedAt() + config.limitMillis();
        return state.firstSolvedAt() < 0 ? limit : Math.min(limit, state.firstSolvedAt() + config.closeAfterFirstMillis());
    }

    /** 지금 단계가 끝날 때까지 남은 시간(ms, 0 이상) — 공개 뷰용(봇 이정표는 넣지 않는다). 끝난 매치는 0. */
    public long remainingMillis(StarSquareState state, long now) {
        long end = switch (state.phase()) {
            case INTRO -> state.phaseStartedAt() + config.introMillis();
            case PLAYING -> deadline(state);
            case RESULT -> state.phaseStartedAt() + config.resultMillis();
            case ENDED -> now;
        };
        return Math.max(0, end - now);
    }

    /** 포트 ⑦ — 다음 시간 전이까지 남은 시간(0 이하면 만기). 끝난 매치면 empty. */
    public OptionalLong timer(StarSquareState state, long now) {
        OptionalLong due = nextDue(state);
        return due.isPresent() ? OptionalLong.of(due.getAsLong() - now) : OptionalLong.empty();
    }

    private OptionalLong nextDue(StarSquareState state) {
        return switch (state.phase()) {
            case INTRO -> OptionalLong.of(state.phaseStartedAt() + config.introMillis());
            case PLAYING -> {
                long at = deadline(state);
                BotDue bot = nextBot(state);
                yield OptionalLong.of(bot == null ? at : Math.min(at, bot.at()));
            }
            case RESULT -> OptionalLong.of(state.phaseStartedAt() + config.resultMillis());
            case ENDED -> OptionalLong.empty();
        };
    }

    private record BotDue(int seat, long at) {
    }

    private BotDue nextBot(StarSquareState state) {
        BotDue best = null;
        for (int seat : state.aliveSeats()) {
            SeatBoard b = state.board(seat);
            if (b.pace() == null || b.solved() || b.pace().done()) {
                continue;
            }
            long at = state.phaseStartedAt() + b.pace().milestoneAt(b.pace().reached() + 1);
            if (best == null || at < best.at()) {
                best = new BotDue(seat, at);
            }
        }
        return best;
    }

    /**
     * 포트 ⑦ — {@code now} 까지 닿은 시간 전이를 시각 순서대로 모두 처리한다(봇 이정표들 → 마감 → 결과 보기 끝 → 다음 퍼즐 …).
     * 닿은 것이 없으면 이벤트 없는 같은 상태(부르는 쪽이 {@link #timer} 로 먼저 본다).
     */
    public Result onTimer(StarSquareState state, long now) {
        List<StarSquareEvent> events = new ArrayList<>();
        StarSquareState cur = state;
        for (int guard = 0; guard < 1_000 && !cur.ended(); guard++) {
            if (cur.phase() == Phase.INTRO) {
                if (cur.phaseStartedAt() + config.introMillis() > now) {
                    break;
                }
                cur = startPuzzle(cur, 0, now, events);
            } else if (cur.phase() == Phase.RESULT) {
                if (cur.phaseStartedAt() + config.resultMillis() > now) {
                    break;
                }
                cur = startPuzzle(cur, cur.index() + 1, now, events);
            } else {
                long deadline = deadline(cur);
                BotDue bot = nextBot(cur);
                if (bot != null && bot.at() <= now && bot.at() <= deadline) {
                    cur = botStep(cur, bot.seat(), now, events);
                } else if (deadline <= now) {
                    cur = endPuzzle(cur, closedReason(cur), now, events);
                } else {
                    break;
                }
            }
        }
        return new Result(cur, events);
    }

    private PuzzleOutcome.EndReason closedReason(StarSquareState state) {
        long limit = state.phaseStartedAt() + config.limitMillis();
        return state.firstSolvedAt() >= 0 && state.firstSolvedAt() + config.closeAfterFirstMillis() <= limit
                ? PuzzleOutcome.EndReason.CLOSED
                : PuzzleOutcome.EndReason.TIME_UP;
    }

    /** 봇 이정표 하나 — 계획된 칸들을 정답 값으로 채운다. 마지막 이정표면 완성(§10-2). */
    private StarSquareState botStep(StarSquareState state, int seat, long now, List<StarSquareEvent> events) {
        SeatBoard board = state.board(seat);
        BotPlan pace = board.pace();
        int j = pace.reached() + 1;
        Grid solution = state.puzzle().solution();
        Grid grid = board.grid();
        for (int k = pace.cellsThrough(j - 1); k < pace.cellsThrough(j); k++) {
            int cell = pace.order().get(k);
            grid = grid.with(cell, solution.get(cell));
        }
        SeatBoard next = board.withGrid(grid, board.version()).withPace(pace.advanced());
        StarSquareState updated = state.withBoard(seat, next);
        if (j == pace.milestones()) {
            // 완성 시각은 계획 시각 — 다만 먼저 처리된 완성보다 빠르게 적지 않는다(표시 순서가 완성 순서와 맞게)
            long earlier = state.boards().stream().filter(SeatBoard::solved).mapToLong(SeatBoard::elapsedMs).max()
                    .orElse(0);
            return markSolved(updated, seat, Math.max(pace.milestoneAt(j), earlier), now, events);
        }
        if (!next.progress().equals(board.progress())) {
            events.add(new StarSquareEvent.Progress(seat, next.filled(), next.lines()));
        }
        return updated;
    }

    // ---- 탈주 (§12.1) ----

    public Desertion desert(StarSquareState state, int seat, long now) {
        if (state.ended() || seat < 0 || seat >= state.seatCount() || !state.alive(seat)) {
            return new Desertion(state, Desertion.Outcome.NOT_APPLICABLE, List.of());
        }
        List<Integer> deserted = new ArrayList<>(state.deserted());
        deserted.add(seat);
        StarSquareState marked = state.withDeserted(deserted);
        List<StarSquareEvent> events = new ArrayList<>();
        events.add(new StarSquareEvent.SeatDeserted(seat));
        StarSquareState out;
        List<Integer> alive = marked.aliveSeats();
        if (alive.stream().allMatch(config::bot)) {
            out = finishMatch(marked, MatchResult.EndReason.NO_HUMANS, now, events);
        } else if (marked.seatCount() > 1 && alive.size() == 1) {
            out = finishMatch(marked, MatchResult.EndReason.LAST_SEAT, now, events);
        } else if (marked.phase() == Phase.PLAYING && allAliveSolved(marked)) {
            out = endPuzzle(marked, PuzzleOutcome.EndReason.ALL_SOLVED, now, events);
        } else {
            out = marked;
        }
        Desertion.Outcome outcome = out.ended() ? Desertion.Outcome.MATCH_ENDED : Desertion.Outcome.MATCH_CONTINUES;
        return new Desertion(out, outcome, events);
    }

    // ---- 진행 질의 (§9-5·§9-6) ----

    /** 포트 ④ — 푸는 단계의 살아 있고 아직 완성하지 않은 사람 좌석(오름차순). 그 밖에는 빈 목록. */
    public List<Integer> pendingSeats(StarSquareState state) {
        if (state.phase() != Phase.PLAYING) {
            return List.of();
        }
        return state.aliveSeats().stream()
                .filter(s -> !config.bot(s) && !state.board(s).solved())
                .toList();
    }

    /**
     * 결정적 폴백(§9-6) — 그 좌석의 저장된 판을 저장된 버전 그대로 다시 보내는 {@code SYNC}. 받으면 무변화다(§7-3) — 인프라가 재무장하며
     * 사라진 엔진 타이머를 다시 거는 효과만 있다. 포트 {@code timeoutAction}·{@code botAction} 이 쓴다.
     */
    public StarSquareAction.Sync fallbackAction(StarSquareState state, int seat) {
        SeatBoard board = state.board(seat);
        return new StarSquareAction.Sync(state.index(), board.version(), board.grid().toList());
    }

    /** 대기 좌석이면 폴백 하나, 아니면 빈 목록 — 사람의 수는 판 전체라 나열할 수 없다. */
    public List<StarSquareAction> legalActions(StarSquareState state, int seat) {
        return pendingSeats(state).contains(seat) ? List.of(fallbackAction(state, seat)) : List.of();
    }

    private static StarSquareActionRejectedException reject(RejectionReason reason) {
        return new StarSquareActionRejectedException(reason);
    }
}
