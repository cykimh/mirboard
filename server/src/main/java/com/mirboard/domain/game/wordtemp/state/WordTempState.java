package com.mirboard.domain.game.wordtemp.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mirboard.domain.game.core.GameState;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 낱말 온도 한 판의 전체 상태(`docs/rules-wordtemp.md`, 설계서 §3). 1판 = 1매치.
 *
 * <p><b>서버만 본다.</b> 후보·정답·출제자의 힌트와 온도·공개 전 추리 답이 들어 있다 — 누가 무엇을 보는지는 {@link WordTempVisibility} 한 곳이
 * 정하고, 공개 뷰·비공개 뷰는 {@link WordTempViews} 가 그 함수를 거쳐 따로 만든다(공개 뷰 타입에는 비밀 필드 자체가 없다).
 *
 * <p>Redis 에 JSON 으로 저장된다(S2). 모르는 필드는 무시하고, 없는 목록은 빈 목록으로 읽는다. 시각은 전부 ms 이고 <b>단계 시작 시각</b>을
 * 상태에 둔다 — 타이머를 다시 걸어도 처음부터 세지 않는다(D-183 방식).
 *
 * @param seats          좌석별 상태(좌석 수만큼, 좌석 번호 = 인덱스 — 빠져도 줄지 않는다 D-122)
 * @param order          출제 순서(좌석 번호의 순열 — 무작위 시작 좌석부터 +1 씩, 라운드마다 같다)
 * @param round          지금 라운드(1 부터)
 * @param turnNo         판 전체 출제 차례 일련번호(1 부터, 첫 낱말 고르기 동안은 0)
 * @param setterSeat     이번 출제자, 낱말 고르기 동안은 −1
 * @param attempt        차수 1~3, 낱말 고르기 동안은 0
 * @param phase          단계
 * @param phaseStartedAt 지금 단계가 시작된 시각
 * @param answer         이번 출제 차례의 정답(낱말 자체 — 표 인덱스가 아니다). 낱말 고르기 동안은 null
 * @param hints          이번 출제 차례에 낸 힌트 전부(모든 차수, 무효 포함)
 * @param revealed       차수별 공개 힌트(힌트 단계가 끝날 때 하나씩 쌓인다)
 * @param guesses        <b>지금 차수</b>에 낸 추리(공개 전) — 공개되면 {@code attempts} 로 옮겨 가고 비운다
 * @param attempts       이번 출제 차례의 끝난 차수 결과(공개된 추리·출제자 점수)
 * @param botPlan        봇이 행동할 시각(엔진 타이머가 실행한다 — §11)
 * @param history        끝난 출제 차례 요약(결과 화면·기록용)
 * @param liveRequests   이 판에서 실시간 채점을 요청한 횟수(판 상한 — 설계서 §5.5)
 * @param seed           씨앗 — 출제 순서·무작위 고르기·봇의 결정이 여기서 나온다
 * @param result         끝났으면 결과, 아니면 null
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WordTempState(List<Seat> seats,
                            List<Integer> order,
                            int round,
                            int turnNo,
                            int setterSeat,
                            int attempt,
                            Phase phase,
                            long phaseStartedAt,
                            AnswerEntry answer,
                            List<Hint> hints,
                            List<Revealed> revealed,
                            List<Guess> guesses,
                            List<AttemptResult> attempts,
                            List<BotMove> botPlan,
                            List<TurnSummary> history,
                            int liveRequests,
                            long seed,
                            MatchResult result) implements GameState {

    public WordTempState {
        seats = List.copyOf(seats);
        order = List.copyOf(order);
        hints = hints == null ? List.of() : List.copyOf(hints);
        revealed = revealed == null ? List.of() : List.copyOf(revealed);
        guesses = guesses == null ? List.of() : List.copyOf(guesses);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        botPlan = botPlan == null ? List.of() : List.copyOf(botPlan);
        history = history == null ? List.of() : List.copyOf(history);
    }

    // ---- 값들 ----

    /** 힌트의 상태(§6.2). */
    public enum HintStatus { SCORED, PENDING, VOID }

    /** 온도가 어디서 왔나 — 표 / 실시간 채점. */
    public enum HintSource { TABLE, LIVE }

    /** 무효 사유(§6.2). */
    public enum VoidReason { NOT_A_NOUN, SCORING_FAILED }

    /** 출제 차례가 끝난 이유(§12.2 {@code TURN_ENDED.reason}). */
    public enum TurnEndReason {
        SOLVED,
        /** 마지막 차수까지 아무도 못 맞혔다(D-320 — 5차). */
        ALL_ATTEMPTS,
        /** 옛 판(룰 v1)의 기록에만 남는다 — 새로 만들지 않는다. */
        THREE_ATTEMPTS,
        /** 〃 (v2 에서는 힌트 0개여도 추리로 간다). */
        NO_HINTS,
        SETTER_OUT
    }

    /** 판이 끝난 이유(§8). */
    public enum EndReason { FINISHED, LAST_SEAT, NO_HUMANS }

    /** 봇 행동의 종류. 힌트 봇은 한 번에 하나(다음 힌트 또는 [힌트 끝]은 그때 정한다). */
    public enum BotMoveKind { HINT, GUESS }

    /**
     * @param alive        살아 있는가(탈주·강퇴로 빠지면 false — 좌석은 그대로)
     * @param setterPoints 출제 점수 합
     * @param guessPoints  추리 점수 합
     * @param candidates   후보 낱말({@code max(3, R+1)} 개) — <b>본인만</b> 본다, 안 쓴 것은 끝까지 비공개
     * @param used         앞 라운드들에서 쓴 후보 번호
     * @param chosen       이번 라운드에 고른 후보 번호, 아직이면 −1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seat(boolean alive, int setterPoints, int guessPoints, List<AnswerEntry> candidates,
                       List<Integer> used, int chosen) {

        public Seat {
            candidates = List.copyOf(candidates);
            used = used == null ? List.of() : List.copyOf(used);
        }

        public int total() {
            return setterPoints + guessPoints;
        }

        /** 이번 라운드에 고를 수 있는 후보인가. */
        public boolean available(int index) {
            return index >= 0 && index < candidates.size() && !used.contains(index);
        }

        public Seat out() {
            return new Seat(false, setterPoints, guessPoints, candidates, used, chosen);
        }

        public Seat chose(int index) {
            return new Seat(alive, setterPoints, guessPoints, candidates, used, index);
        }

        /** 새 라운드 — 고른 것을 쓴 것으로 옮긴다. */
        public Seat nextRound() {
            if (chosen < 0) {
                return this;
            }
            List<Integer> next = new ArrayList<>(used);
            next.add(chosen);
            return new Seat(alive, setterPoints, guessPoints, candidates, next, -1);
        }

        public Seat plus(int setter, int guess) {
            return new Seat(alive, setterPoints + setter, guessPoints + guess, candidates, used, chosen);
        }
    }

    /**
     * @param text        정규화된 힌트
     * @param attempt     낸 차수
     * @param status      확정 / 채점 중 / 무효
     * @param temp        확정 온도 0~100, 아니면 −1 — <b>확정된 온도는 여기서 읽는다</b>(resync·기록이 다시 채점하지 않는다, §6.3)
     * @param source      표 / 실시간
     * @param requestedAt 채점을 요청한 시각(실시간), 표면 0 — 기다림 상한의 기준
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hint(String text, int attempt, HintStatus status, int temp, HintSource source, long requestedAt) {

        public Hint scored(int temp) {
            return new Hint(text, attempt, HintStatus.SCORED, temp, source, requestedAt);
        }

        public Hint voided() {
            return new Hint(text, attempt, HintStatus.VOID, -1, source, requestedAt);
        }
    }

    /** 차수의 공개 힌트 — 그 차수에 낸 것 중 온도가 가장 높은 하나(같으면 먼저 낸 것). {@code floor} = 그 차수 최저 온도. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revealed(int attempt, String text, int temp, int floor, boolean floorMet) {
    }

    /** 공개 전 추리. {@code text} 는 정규화된 답, 빈 문자열 = 패스. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guess(int seat, String text) {

        public boolean pass() {
            return text.isEmpty();
        }
    }

    /** 공개된 추리 하나. {@code masked} = 금칙어에 걸려 남에게 가려 보인다(§12) — 원문은 상태에만 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuessEntry(int seat, String text, boolean correct, boolean masked) {

        public boolean pass() {
            return text.isEmpty();
        }
    }

    /** 끝난 차수 하나. {@code setterGain} = 그 차수에 출제자가 받은 점수(라운드 배수 포함). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttemptResult(int attempt, List<GuessEntry> guesses, int setterGain) {

        public AttemptResult {
            guesses = List.copyOf(guesses);
        }

        public List<Integer> solvers() {
            return guesses.stream().filter(GuessEntry::correct).map(GuessEntry::seat).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BotMove(int seat, long at, BotMoveKind kind) {
    }

    /**
     * 끝난 출제 차례 요약 — 정답·공개된 힌트·맞힌 좌석만(오답 원문·미공개 힌트는 없다, §13).
     *
     * @param setterGain 그 차례에 출제자가 받은 점수 합
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnSummary(int turnNo, int round, int setterSeat, String answer, List<Revealed> revealed,
                              List<Integer> solvers, int setterGain, TurnEndReason reason) {

        public TurnSummary {
            revealed = List.copyOf(revealed);
            solvers = List.copyOf(solvers);
        }
    }

    /** {@code rank} 는 공동 순위(1, 1, 3 식). 빠진 좌석은 점수와 무관하게 모두 같은 최하위(§8). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Standing(int seat, int rank, int total, int setterPoints, int guessPoints, boolean out) {
    }

    /** {@code draw} = 빠진 사람 없이 모두 같은 점수(§8 — ELO 변화 없음). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchResult(EndReason endReason, List<Standing> standings, boolean draw) {

        public MatchResult {
            standings = List.copyOf(standings);
        }
    }

    // ---- 읽기 ----

    public int seatCount() {
        return seats.size();
    }

    public Seat seat(int seat) {
        return seats.get(seat);
    }

    public boolean inRange(int seat) {
        return seat >= 0 && seat < seats.size();
    }

    public boolean alive(int seat) {
        return inRange(seat) && seats.get(seat).alive();
    }

    public List<Integer> aliveSeats() {
        return IntStream.range(0, seatCount()).filter(this::alive).boxed().toList();
    }

    public boolean ended() {
        return phase == Phase.FINISHED;
    }

    /** 출제 차례가 진행 중인가(힌트·추리·차수 결과). */
    public boolean turnInProgress() {
        return phase == Phase.HINT || phase == Phase.GUESS || phase == Phase.ATTEMPT_RESULT;
    }

    /** 이번 차수의 추리자(살아 있고 출제자가 아닌 좌석, 오름차순). */
    public List<Integer> guessers() {
        return aliveSeats().stream().filter(s -> s != setterSeat).toList();
    }

    /** 채점 중인 힌트(한 번에 하나 — §6.2). 없으면 null. */
    public Hint pendingHint() {
        for (Hint h : hints) {
            if (h.status() == HintStatus.PENDING) {
                return h;
            }
        }
        return null;
    }

    /** 이번 차수의 힌트 개수(확정 + 채점 중) — 모두가 보는 "힌트 3/10". */
    public int hintCount() {
        return (int) hints.stream()
                .filter(h -> h.attempt() == attempt && h.status() != HintStatus.VOID).count();
    }

    /** 이번 차수의 가장 높은 힌트 — 확정된 것 중 온도가 가장 높은 하나(같으면 먼저 낸 것). 없으면 null. 기준을 넘겼는지는 이것으로 본다. */
    public Hint bestHint() {
        Hint best = null;
        for (Hint h : hints) {
            if (h.attempt() == attempt && h.status() == HintStatus.SCORED && (best == null || h.temp() > best.temp())) {
                best = h;
            }
        }
        return best;
    }

    public Guess guessOf(int seat) {
        for (Guess g : guesses) {
            if (g.seat() == seat) {
                return g;
            }
        }
        return null;
    }

    public List<Integer> totals() {
        return seats.stream().map(Seat::total).toList();
    }

    /** 지금 차수에 누가 맞혔는가(차수 결과 단계에서만 뜻이 있다). */
    public boolean solvedThisAttempt() {
        return attempts.stream().anyMatch(a -> a.attempt() == attempt && !a.solvers().isEmpty());
    }

    // ---- 고치기 ----

    /** 엔진의 전이용 초안 — 필드를 고친 뒤 {@link Draft#build()} 로 새 상태를 만든다(원본은 그대로). */
    public Draft draft() {
        return new Draft(this);
    }

    public static final class Draft {
        public List<Seat> seats;
        public List<Integer> order;
        public int round;
        public int turnNo;
        public int setterSeat;
        public int attempt;
        public Phase phase;
        public long phaseStartedAt;
        public AnswerEntry answer;
        public List<Hint> hints;
        public List<Revealed> revealed;
        public List<Guess> guesses;
        public List<AttemptResult> attempts;
        public List<BotMove> botPlan;
        public List<TurnSummary> history;
        public int liveRequests;
        public long seed;
        public MatchResult result;

        private Draft(WordTempState s) {
            seats = new ArrayList<>(s.seats);
            order = s.order;
            round = s.round;
            turnNo = s.turnNo;
            setterSeat = s.setterSeat;
            attempt = s.attempt;
            phase = s.phase;
            phaseStartedAt = s.phaseStartedAt;
            answer = s.answer;
            hints = new ArrayList<>(s.hints);
            revealed = new ArrayList<>(s.revealed);
            guesses = new ArrayList<>(s.guesses);
            attempts = new ArrayList<>(s.attempts);
            botPlan = new ArrayList<>(s.botPlan);
            history = new ArrayList<>(s.history);
            liveRequests = s.liveRequests;
            seed = s.seed;
            result = s.result;
        }

        public WordTempState build() {
            return new WordTempState(seats, order, round, turnNo, setterSeat, attempt, phase, phaseStartedAt, answer,
                    hints, revealed, guesses, attempts, botPlan, history, liveRequests, seed, result);
        }

        /** 지금까지 고친 것을 읽기 전용 상태로 — 초안 위에서 상태의 읽기 함수를 쓰려고. */
        public WordTempState peek() {
            return build();
        }
    }
}
