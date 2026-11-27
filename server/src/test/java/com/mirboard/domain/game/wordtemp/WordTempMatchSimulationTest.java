package com.mirboard.domain.game.wordtemp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.wordtemp.WordTempEngine.LiveScore;
import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.action.WordTempAction;
import com.mirboard.domain.game.wordtemp.action.WordTempActionRejectedException;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.event.WordTempEvent;
import com.mirboard.domain.game.wordtemp.invariant.WordTempInvariantChecker;
import com.mirboard.domain.game.wordtemp.rules.CandidateDealer;
import com.mirboard.domain.game.wordtemp.rules.HintRules;
import com.mirboard.domain.game.wordtemp.rules.WordNormalizer;
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.GuessEntry;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.HintView;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.PublicView;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.SeatView;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import com.mirboard.domain.game.wordtemp.words.WordTempWords;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * `docs/rules-wordtemp.md` §17 — 사람 흉내와 봇을 섞고 실시간 채점(가짜 채점기)·잘못된 입력·낡은 TICK·탈주를 넣은 매치가 3~6인 × 판 길이 3종
 * 각 2,000판 모두 끝나고, 매 전이 뒤 불변식을 지키며, 공개 이벤트만 듣는 클라가 그린 판(힌트 목록 포함)이 엔진의 공개 뷰와 같고, 공개 출력에
 * 비밀 문자열이 없다. 통계는 {@code server/build/wordtemp-sim-stats.txt}. 룰 v2(D-320): 차수 5번·기준 70/75/80/85/90·차수당 힌트 10개,
 * 힌트는 낼 때마다 공개, 기준을 넘긴 힌트가 나오면 그 자리에서 추리.
 *
 * <p><b>표는 진짜 표</b>(v1, 정답 324개)다 — 기준 온도를 넘길 힌트가 정답마다 얼마나 있는지가 v2 의 핵심 수치라 합성 표로는 볼 수 없다
 * (진짜 표의 정답 3분의 1 은 90° 이상 쌍이 없다). 조회가 빠르도록 메모리 표로 옮겨 쓴다(내용·순서는 같다).
 *
 * <p><b>사람은 모형이다 — 아래는 전부 가정</b>이고, 그래서 판 길이·점수 몫·기준 세트 비교는 <b>상대적인 방향</b>을 보는 재료다(절대값은 시험판으로).
 * <ul>
 *   <li><b>출제자</b>는 온도를 미리 모른다. 힌트 하나를 떠올려 치는 데 5~14초. 힌트는 그 정답의 표 이웃 중 아직 안 낸 것에서 고른다:
 *       사람마다 판 내내 고정된 {@code 솜씨}(0.20~0.45)의 확률로 <b>기준 이상</b>인 낱말(있으면), 아니면 기준 −25° ~ 기준 −1° 띠의 낱말
 *       (띠가 비면 안 낸 아무 낱말). 힌트의 약 20% 는 표 밖 낱말이다(실시간 채점이 켜져 있으면 채점 중 → 결과, 꺼져 있으면
 *       {@code UNKNOWN_WORD} 거절) — 그 온도는 기준 −35° ~ 기준 +4° 에서 고르게 나온다고 본다(12.5% 가 기준 이상).
 *       기준을 넘기거나, 10개를 다 쓰거나, 시간이 끝날 때까지 계속 낸다. 힌트 단계의 5% 는 3개 이상 낸 뒤 [힌트 끝]을 누르고,
 *       2% 는 자리를 비워 힌트를 하나도 내지 않는다. 가끔 잘못된 입력(형식·정답 포함·중복)으로 거절당한다.</li>
 *   <li><b>추리자</b>가 한 차수에 맞힐 확률은 {@link #hitChance} — 그 출제 차례에 공개된 힌트 중 <b>가장 뜨거운 것의 온도</b>와
 *       <b>공개된 힌트의 개수</b>로 어림한다(뜨거울수록·많을수록 높다). 4~26초에 답하고 6% 는 30초를 넘겨 패스가 된다.</li>
 *   <li>봇 섞기(좌석마다 30%)·탈주(행동마다 0.2%)·힌트 제한(90초가 70%, 나머지 60/120/180)·실시간 채점 켠 판 80% 는 v1 시뮬레이션 그대로.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WordTempMatchSimulationTest {

    private static final int MATCHES = 2_000;
    /** 표가 가질 수 있는 차수 상한(통계 배열 크기). */
    private static final int MAX_ATTEMPTS = 5;
    private static final WordTempWords REAL = new WordTempWords();
    private static final List<AnswerEntry> BANK = REAL.bank();
    private static final InMemorySimilarityTable TABLE = copyOfRealTable();
    /** 정답마다 낼 수 있는 표 이웃(정규화돼 있고 포함 규칙에 안 걸리는 것). */
    private static final Map<String, List<Neighbor>> LEGAL = legalNeighbors();
    /** 표에 없는 낱말들(무작위 음절) — 사람의 표 밖 힌트·틀린 답에 쓴다. */
    private static final List<String> NONSENSE = nonsense();
    private static final Set<String> BLOCKED = Set.of(NONSENSE.get(0), NONSENSE.get(1));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> REPORT = new TreeMap<>();
    private static final int[][] GRID_CELLS = {{3, 1}, {3, 2}, {3, 3}, {4, 1}, {4, 2}, {4, 3}, {5, 1}, {5, 2}, {5, 3},
            {6, 1}, {6, 2}, {6, 3}};
    /** [2] 에서 견주는 기준 세트 — 첫 줄이 기본값. */
    private static final Integer[][] FLOOR_SETS = {{70, 75, 80, 85, 90}, {70, 75, 80, 85, 88}, {65, 70, 75, 80, 85},
            {60, 65, 70, 75, 80}, {70, 70, 75, 80, 85}, {50, 60, 70}};
    private static Map<String, Object> grid;

    private static InMemorySimilarityTable copyOfRealTable() {
        InMemorySimilarityTable copy = new InMemorySimilarityTable();
        for (String answer : REAL.table().answers()) {
            copy.answer(answer);
        }
        for (String answer : REAL.table().answers()) {
            for (Neighbor n : REAL.table().neighbors(answer)) {
                copy.put(answer, n.hint(), n.temp());
            }
        }
        return copy;
    }

    private static Map<String, List<Neighbor>> legalNeighbors() {
        Map<String, List<Neighbor>> out = new HashMap<>();
        for (AnswerEntry answer : BANK) {
            out.put(answer.word(), TABLE.neighbors(answer.word()).stream()
                    .filter(n -> WordNormalizer.normalize(n.hint()).filter(n.hint()::equals).isPresent()
                            && HintRules.containment(answer, n.hint()).isEmpty()).toList());
        }
        return out;
    }

    private static List<String> nonsense() {
        Set<String> real = new HashSet<>(REAL.table().answers());
        for (String answer : REAL.table().answers()) {
            REAL.table().neighbors(answer).forEach(n -> real.add(n.hint()));
        }
        BANK.forEach(e -> real.addAll(e.aliases()));
        return SyntheticWords.generate(0xC0FFEEL, 1, 3_000).vocabulary.stream().filter(w -> !real.contains(w)).toList();
    }

    /** 한 번의 실행(인원·판 길이 하나)의 집계. record 라 같은 씨앗의 재현 비교에 그대로 쓴다. 차수별 배열은 [1..5]. */
    record Stats(int matches, Map<EndReason, Integer> reasons, int transitions, int rejections,
                 Map<RejectionReason, Integer> rejectionsBy, int turns, Map<TurnEndReason, Integer> turnEnds,
                 int[] solvedAtAttempt, int reachedLast, int attempts, int allWrongAttempts, int floorDenied,
                 int[] hintPhases, int[] floorMet, long[] hintsGiven, int zeroHintPhases, int fullHintPhases,
                 int endedEarly, long setterPoints, long guessPoints, int draws, long winnerPoints,
                 long[] cleanDurations, long[] humanOnlyDurations, long[] phaseMs,
                 int livePending, int liveScored, int liveVoid, int liveTimeout, int livePassed, int lateLiveClose,
                 int[] botGuessesBy, int[] botHitsBy, int[] botAttemptGuesses, int[] botAttemptHits, int[] botTurnsBy,
                 int[] botTurnHitsBy, int scoredTurns, double setterGainPerRound, long solverCount, int maxScore) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Stats s && toString().equals(s.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return matches + " " + reasons + " " + transitions + " " + rejections + " " + rejectionsBy + " " + turns
                    + " " + turnEnds + " " + Arrays.toString(solvedAtAttempt) + " " + reachedLast + " " + attempts
                    + " " + allWrongAttempts + " " + floorDenied + " " + Arrays.toString(hintPhases) + " "
                    + Arrays.toString(floorMet) + " " + Arrays.toString(hintsGiven) + " " + setterPoints + " "
                    + guessPoints + " " + draws + " " + Arrays.toString(cleanDurations).hashCode() + " "
                    + Arrays.toString(phaseMs) + " " + livePending + " " + liveScored + " " + liveVoid + " "
                    + Arrays.toString(botGuessesBy) + " " + Arrays.toString(botHitsBy) + " " + setterGainPerRound;
        }
    }

    /** 실행 조건. */
    record Options(double botRate, double desertRate, double liveRate, WordTempTuning tuning, int fixedHintSeconds,
                   int secrecyMatches) {

        static Options mixed() {
            return new Options(0.3, 0.002, 0.8, WordTempTuning.DEFAULT, 0, 25);
        }

        /** 사람만·탈주 없음·힌트 90초 — 기준 세트 비교용. */
        static Options humansOnly(WordTempTuning tuning) {
            return new Options(0.0, 0.0, 0.8, tuning, 90, 0);
        }
    }

    // ---- 테스트 ----

    /** 12칸을 한 번에(병렬로) 돌린다 — 칸마다 씨앗이 따로라 결과는 순서와 무관하게 같다. 칸의 실패는 그 칸의 테스트가 다시 던진다. */
    private static synchronized Map<String, Object> grid() {
        if (grid == null) {
            Map<String, Object> out = new ConcurrentHashMap<>();
            Arrays.stream(GRID_CELLS).parallel().forEach(cell -> {
                try {
                    out.put(cell[0] + "-" + cell[1],
                            run(cell[0], cell[1], MATCHES, 0x77AA0000L + cell[0] * 10L + cell[1], Options.mixed()));
                } catch (Throwable failure) {
                    out.put(cell[0] + "-" + cell[1], failure);
                }
            });
            grid = out;
        }
        return grid;
    }

    @ParameterizedTest(name = "{0}인 {1}라운드 2,000매치")
    @CsvSource({"3,1", "3,2", "3,3", "4,1", "4,2", "4,3", "5,1", "5,2", "5,3", "6,1", "6,2", "6,3"})
    @Timeout(900)
    @Order(1)
    void random_matches_always_end_and_keep_every_invariant(int seats, int rounds) throws Throwable {
        Object cell = grid().get(seats + "-" + rounds);
        if (cell instanceof Throwable failure) {
            throw failure;
        }
        Stats s = (Stats) cell;
        assertThat(s.matches()).isEqualTo(MATCHES);
        assertThat(s.reasons().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(MATCHES);
        assertThat(s.reasons().getOrDefault(EndReason.FINISHED, 0)).isGreaterThan(MATCHES * 7 / 10);
        // 모든 갈래가 실제로 밟혔다 — v2 에서 나오는 끝은 셋, 옛 값 둘은 나오지 않는다
        for (TurnEndReason reason : List.of(TurnEndReason.SOLVED, TurnEndReason.ALL_ATTEMPTS, TurnEndReason.SETTER_OUT)) {
            assertThat(s.turnEnds().getOrDefault(reason, 0)).as("turn end %s", reason).isPositive();
        }
        assertThat(s.turnEnds()).doesNotContainKeys(TurnEndReason.NO_HINTS, TurnEndReason.THREE_ATTEMPTS);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThat(s.solvedAtAttempt()[attempt]).as("solved at attempt %d", attempt).isPositive();
            assertThat(s.hintPhases()[attempt]).isPositive();
            assertThat(s.floorMet()[attempt]).as("floor met at attempt %d", attempt).isPositive();
            assertThat(s.floorMet()[attempt]).as("floor not met at attempt %d", attempt)
                    .isLessThan(s.hintPhases()[attempt]);
        }
        assertThat(s.reachedLast()).isPositive();
        assertThat(s.floorDenied()).as("전원 오답인데 기준 미달이라 출제자 0점").isPositive();
        assertThat(s.allWrongAttempts()).as("전원 오답이고 기준을 넘겨 출제자가 점수를 받음").isGreaterThan(s.floorDenied());
        assertThat(s.zeroHintPhases()).as("힌트 0개로 추리에 간 차수").isPositive();
        assertThat(s.fullHintPhases()).as("10개를 다 쓰고도 기준 미달").isPositive();
        assertThat(s.endedEarly()).as("[힌트 끝]").isPositive();
        assertThat(s.livePending()).isPositive();
        assertThat(s.liveScored()).isPositive();
        assertThat(s.liveVoid()).isPositive();
        assertThat(s.liveTimeout()).isPositive();
        assertThat(s.livePassed()).as("실시간 채점 결과가 기준을 넘겨 단계가 끝남").isPositive();
        assertThat(s.lateLiveClose()).as("마감 뒤에 온 채점 결과가 단계를 닫음").isPositive();
        assertThat(Arrays.stream(s.botGuessesBy()).sum()).isPositive();
        assertThat(s.rejections()).isPositive();
        assertThat(s.setterPoints()).isPositive();
        assertThat(s.guessPoints()).isPositive();
        // 한 판 최고점(§4): 출제 15r 씩(1+2+3+4+5) + 추리 r × (좌석 − 1)
        int cap = 0;
        for (int r = 1; r <= rounds; r++) {
            cap += 15 * r + r * (seats - 1);
        }
        assertThat(s.maxScore()).isLessThanOrEqualTo(cap);
        REPORT.put("1-" + seats + "-" + rounds, gridLine(seats, rounds, s));
    }

    @Test
    @Timeout(300)
    @Order(2)
    void the_same_seed_replays_the_same_matches() {
        assertThat(run(4, 2, 60, 99L, Options.mixed())).isEqualTo(run(4, 2, 60, 99L, Options.mixed()));
        assertThat(run(4, 2, 60, 99L, Options.mixed())).isNotEqualTo(run(4, 2, 60, 100L, Options.mixed()));
    }

    /**
     * 기준 세트 비교 — 사람 4명·2라운드·힌트 90초. 기준이 낮을수록 넘기기 쉽고(차수별 도달률), 출제자 몫이 커진다. 같은 씨앗으로 돌려
     * 세트만 다르다.
     */
    @Test
    @Timeout(600)
    @Order(3)
    void lower_floors_are_met_more_often_and_pay_the_setter_more() {
        Map<Integer, Stats> bySet = new ConcurrentHashMap<>();
        java.util.stream.IntStream.range(0, FLOOR_SETS.length).parallel().forEach(i -> bySet.put(i,
                run(4, 2, MATCHES, 0x51A7L, Options.humansOnly(WordTempTuning.DEFAULT.withFloors(FLOOR_SETS[i])))));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < FLOOR_SETS.length; i++) {
            Stats s = bySet.get(i);
            int last = FLOOR_SETS[i].length;
            StringBuilder reach = new StringBuilder();
            StringBuilder hints = new StringBuilder();
            for (int a = 1; a <= last; a++) {
                reach.append(a == 1 ? "" : "/").append(String.format(Locale.ROOT, "%.0f",
                        100.0 * s.floorMet()[a] / Math.max(1, s.hintPhases()[a])));
                hints.append(a == 1 ? "" : "/").append(String.format(Locale.ROOT, "%.1f",
                        (double) s.hintsGiven()[a] / Math.max(1, s.hintPhases()[a])));
            }
            out.append(String.format(Locale.ROOT,
                    "  기준 %-20s | 판 길이 %s | 출제 몫 %4.1f%% | 기준 도달(차수별) %s%% | 차수당 힌트 %s"
                            + " | 차례당 차수 %.2f · 마지막(%d차)까지 %4.1f%% | 출제자 차례당 %.2f점 · 추리자 합 %.2f점 (÷ 라운드 배수)%n",
                    Arrays.toString(FLOOR_SETS[i]), minutes(s.cleanDurations()),
                    100.0 * s.setterPoints() / (s.setterPoints() + s.guessPoints()), reach, hints,
                    (double) s.attempts() / s.turns(), last, 100.0 * s.reachedLast() / s.turns(),
                    s.setterGainPerRound() / s.scoredTurns(), (double) s.solverCount() / s.scoredTurns()));
            assertThat(s.reasons()).containsOnlyKeys(EndReason.FINISHED);
            assertThat(s.solvedAtAttempt()[last]).isPositive();
            for (int a = last + 1; a <= MAX_ATTEMPTS; a++) {
                assertThat(s.hintPhases()[a]).as("세트의 차수 수를 넘는 차수는 없다").isZero();
            }
        }
        assertThat(WordTempTuning.DEFAULT.withFloors(FLOOR_SETS[1])).isEqualTo(WordTempTuning.DEFAULT); // 지금 값 = 88 (D-320 덧붙임)
        Stats base = bySet.get(0);
        Stats lower = bySet.get(3); // 60/65/70/75/80
        // 1~3차는 두 세트 모두 넘길 낱말이 거의 늘 있어 차이가 잡음 수준이다 — 넘길 낱말이 드문 4·5차에서 본다
        for (int a = 4; a <= 5; a++) {
            assertThat((double) lower.floorMet()[a] / lower.hintPhases()[a]).as("기준이 낮으면 더 자주 넘긴다 — %d차", a)
                    .isGreaterThan((double) base.floorMet()[a] / base.hintPhases()[a]);
        }
        assertThat(lower.setterGainPerRound() / lower.scoredTurns())
                .isGreaterThan(base.setterGainPerRound() / base.scoredTurns());
        REPORT.put("2-floors", out.toString());
    }

    @AfterAll
    static void writeStats() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("낱말 온도 시뮬레이션 — 룰 v2(D-320): 차수 5번·기준 70/75/80/85/90·차수당 힌트 10개 (씨앗 고정 — 진짜 표 ")
                .append(REAL.table().version()).append(" · 정답 ").append(BANK.size()).append("개, 사람은 모형)\n")
                .append("한계: 사람 출제자·추리자는 가정이다(WordTempMatchSimulationTest 의 클래스 설명·hitChance). 절대값이 아니라 방향을 본다.\n")
                .append("  출제자: 힌트 하나 5~14초, 솜씨(0.20~0.45)의 확률로 기준 이상 낱말·아니면 기준−25°~기준−1°, 20% 는 표 밖 낱말, 5% 는 3개 뒤 [힌트 끝], 2% 는 힌트 없음\n")
                .append("  추리자: 차수마다 맞힐 확률 = f(그 차례에 공개된 가장 뜨거운 힌트의 온도) × (1 + 0.04 × (공개 힌트 수 − 1, 최대 15)) × 사람 솜씨(0.6~1.4)\n\n")
                .append("[1] 인원 × 판 길이 각 ").append(MATCHES)
                .append("판 — 봇 30% 섞음·힌트 제한 60/90/120/180(90 이 70%)·탈주 0.2%/행동·실시간 채점 켠 판 80%\n")
                .append("    판 길이 = 탈주 없이 끝까지 간 판의 분(평균 / 중앙 / 90% / 최대), 괄호는 사람만의 판 평균\n")
                .append("    기준 도달 = 그 차수의 힌트 단계 중 기준을 넘긴 비율(봇 출제 포함). 봇 추리 적중은 차수당(1~5차)과 출제 차례 누적\n");
        REPORT.forEach((key, line) -> {
            if (key.startsWith("1-")) {
                out.append(line);
            }
        });
        out.append("\n[2] 기준 세트 비교 — 사람 4명·2라운드·힌트 90초·").append(MATCHES).append("판(같은 씨앗), 봇·탈주 없음\n")
                .append(REPORT.getOrDefault("2-floors", "  (이 실행에서는 돌지 않음)\n"));
        Path file = Path.of("build", "wordtemp-sim-stats.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.print(out);
    }

    private static String perAttempt(int[] num, int[] den) {
        StringBuilder out = new StringBuilder();
        for (int a = 1; a <= MAX_ATTEMPTS; a++) {
            out.append(a == 1 ? "" : "/").append(String.format(Locale.ROOT, "%.0f", 100.0 * num[a] / Math.max(1, den[a])));
        }
        return out.toString();
    }

    private static String gridLine(int seats, int rounds, Stats s) {
        long total = s.setterPoints() + s.guessPoints();
        long phaseTotal = Arrays.stream(s.phaseMs()).sum();
        StringBuilder phases = new StringBuilder();
        for (Phase p : List.of(Phase.WORD_SELECT, Phase.HINT, Phase.GUESS, Phase.ATTEMPT_RESULT, Phase.TURN_END)) {
            phases.append(String.format(Locale.ROOT, " %s %.0f%%", p, 100.0 * s.phaseMs()[p.ordinal()] / phaseTotal));
        }
        StringBuilder solved = new StringBuilder();
        StringBuilder hints = new StringBuilder();
        int[] turnsArr = new int[MAX_ATTEMPTS + 1];
        Arrays.fill(turnsArr, s.turns());
        for (int a = 1; a <= MAX_ATTEMPTS; a++) {
            hints.append(a == 1 ? "" : "/").append(String.format(Locale.ROOT, "%.1f",
                    (double) s.hintsGiven()[a] / Math.max(1, s.hintPhases()[a])));
        }
        solved.append(perAttempt(s.solvedAtAttempt(), turnsArr));
        long allPhases = Arrays.stream(s.hintPhases()).sum();
        long allHints = Arrays.stream(s.hintsGiven()).sum();
        StringBuilder bots = new StringBuilder();
        String[] names = {"쉬움", "보통", "어려움"};
        for (int l = 0; l < 3; l++) {
            StringBuilder by = new StringBuilder();
            for (int a = 1; a <= MAX_ATTEMPTS; a++) {
                by.append(a == 1 ? "" : "/").append(String.format(Locale.ROOT, "%.0f",
                        100.0 * s.botAttemptHits()[l * 6 + a] / Math.max(1, s.botAttemptGuesses()[l * 6 + a])));
            }
            bots.append(String.format(Locale.ROOT, " %s 차수당 %.1f%%(1~5차 %s) · 차례 누적 %.1f%%", names[l],
                    100.0 * s.botHitsBy()[l] / Math.max(1, s.botGuessesBy()[l]), by,
                    100.0 * s.botTurnHitsBy()[l] / Math.max(1, s.botTurnsBy()[l]))).append(l < 2 ? " |" : "");
        }
        return String.format(Locale.ROOT,
                "  %d인 %d라운드 | 판 길이 %s (사람만 %s) | 끝 %s%n"
                        + "              점수: 출제 몫 %.1f%% · 추리 몫 %.1f%% · 1등 평균 %.1f · 최고 %d · 무승부 %.1f%%"
                        + " | 차례당 차수 %.2f · 5차까지 %.1f%% · 맞힘 1~5차 %s%% · 끝 %s%n"
                        + "              기준 도달 1~5차 %s%% · 차수당 힌트 %.1f개(1~5차 %s) · 힌트 0개 %.1f%% · 10개 다 쓰고 미달 %.1f%% · [힌트 끝] %.1f%%"
                        + " · 전원 오답 차수 중 출제자 0점 %.1f%%%n"
                        + "              시간 몫:%s | 실시간 채점 %d(확정 %d·그중 기준 통과 %d·무효 %d 중 시간 초과 %d)%n"
                        + "              봇 추리 적중:%s | 전이 %d · 거절 %d %s%n",
                seats, rounds, minutes(s.cleanDurations()), average(s.humanOnlyDurations()), s.reasons(),
                100.0 * s.setterPoints() / total, 100.0 * s.guessPoints() / total,
                (double) s.winnerPoints() / s.matches(), s.maxScore(), 100.0 * s.draws() / s.matches(),
                (double) s.attempts() / s.turns(), 100.0 * s.reachedLast() / s.turns(), solved, s.turnEnds(),
                perAttempt(s.floorMet(), s.hintPhases()), (double) allHints / Math.max(1, allPhases), hints,
                100.0 * s.zeroHintPhases() / Math.max(1, allPhases), 100.0 * s.fullHintPhases() / Math.max(1, allPhases),
                100.0 * s.endedEarly() / Math.max(1, allPhases),
                100.0 * s.floorDenied() / Math.max(1, s.allWrongAttempts()), phases, s.livePending(), s.liveScored(),
                s.livePassed(), s.liveVoid(), s.liveTimeout(), bots, s.transitions(), s.rejections(),
                s.rejectionsBy());
    }

    private static String minutes(long[] durations) {
        if (durations.length == 0) {
            return "-";
        }
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%.1f / %.1f / %.1f / %.1f분", Arrays.stream(sorted).average().orElse(0)
                / 60_000, sorted[sorted.length / 2] / 60_000.0, sorted[sorted.length * 9 / 10] / 60_000.0,
                sorted[sorted.length - 1] / 60_000.0);
    }

    private static String average(long[] durations) {
        return durations.length == 0 ? "-"
                : String.format(Locale.ROOT, "%.1f분", Arrays.stream(durations).average().orElse(0) / 60_000);
    }

    // ---- 사람 모형 ----

    /**
     * 추리자가 이번 차수에 맞힐 확률(가정) — 그 출제 차례에 공개된 힌트 중 가장 뜨거운 것의 온도가 바탕이고, 공개된 힌트가 많을수록
     * (하나 더마다 +4%, 15개까지) 높아진다. 힌트가 없으면 거의 0.
     *
     * @param hottest 공개된(확정) 힌트 중 가장 높은 온도, 없으면 −1
     * @param hints   공개된(확정) 힌트 수 — 이 출제 차례의 모든 차수
     * @param skill   사람마다의 배수(0.6~1.4)
     */
    static double hitChance(int hottest, int hints, double skill) {
        if (hints <= 0) {
            return 0.002;
        }
        double base = hottest < 40 ? 0.005 : hottest < 60 ? 0.02 : hottest < 70 ? 0.05 : hottest < 75 ? 0.08
                : hottest < 80 ? 0.12 : hottest < 85 ? 0.17 : hottest < 90 ? 0.23 : hottest < 96 ? 0.32 : 0.70;
        double breadth = 1.0 + 0.04 * Math.min(hints - 1, 15);
        return Math.min(0.95, base * breadth * skill);
    }

    /** 사람 좌석 하나의 흉내. */
    private static final class Human {
        /** 추리 솜씨 배수. */
        final double skill;
        /** 출제 솜씨 — 힌트 하나가 기준 이상에서 나올 확률(판 내내 고정). */
        final double setterSkill;
        long key = -1;
        long nextAt = Long.MAX_VALUE;
        boolean willEnd;
        boolean away;
        boolean fumbled;

        Human(double skill, double setterSkill) {
            this.skill = skill;
            this.setterSkill = setterSkill;
        }
    }

    /** 가짜 실시간 채점기에 맡겨 둔 일. */
    private record ScoreJob(String hint, long at, LiveScore result) {
    }

    // ---- 시뮬레이터 ----

    private static Stats run(int seats, int rounds, int matches, long seed, Options options) {
        SplittableRandom rng = new SplittableRandom(seed);
        WordTempTuning tuning = options.tuning();
        int lastAttempt = tuning.maxAttempts();
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        Map<RejectionReason, Integer> rejectionsBy = new EnumMap<>(RejectionReason.class);
        Map<TurnEndReason, Integer> turnEnds = new EnumMap<>(TurnEndReason.class);
        int[] solvedAt = new int[MAX_ATTEMPTS + 1];
        int[] hintPhases = new int[MAX_ATTEMPTS + 1];
        int[] floorMet = new int[MAX_ATTEMPTS + 1];
        long[] hintsGiven = new long[MAX_ATTEMPTS + 1];
        long[] phaseMs = new long[Phase.values().length];
        List<Long> clean = new ArrayList<>();
        List<Long> humanOnly = new ArrayList<>();
        int transitions = 0;
        int rejections = 0;
        int turns = 0;
        int reachedLast = 0;
        int attempts = 0;
        int allWrong = 0;
        int floorDenied = 0;
        int zeroHintPhases = 0;
        int fullHintPhases = 0;
        int endedEarly = 0;
        long setterPoints = 0;
        long guessPoints = 0;
        int draws = 0;
        long winnerPoints = 0;
        int livePending = 0;
        int liveScored = 0;
        int liveVoid = 0;
        int liveTimeout = 0;
        int livePassed = 0;
        int lateLiveClose = 0;
        int levels = WordTempBotLevel.values().length;
        int[] botGuessesBy = new int[levels];
        int[] botHitsBy = new int[levels];
        int[] botAttemptGuesses = new int[levels * 6];
        int[] botAttemptHits = new int[levels * 6];
        int[] botTurnsBy = new int[levels];
        int[] botTurnHitsBy = new int[levels];
        int scoredTurns = 0;
        double setterGainPerRound = 0;
        long solverCount = 0;
        int maxScore = 0;

        for (int m = 0; m < matches; m++) {
            Set<Integer> bots = new HashSet<>();
            int human = rng.nextInt(seats);
            for (int s = 0; s < seats; s++) {
                if (s != human && rng.nextDouble() < options.botRate()) {
                    bots.add(s);
                }
            }
            int hintSeconds = options.fixedHintSeconds() > 0 ? options.fixedHintSeconds()
                    : rng.nextInt(10) < 7 ? 90 : new int[] {60, 120, 180}[rng.nextInt(3)];
            WordTempBotLevel level = WordTempBotLevel.values()[rng.nextInt(3)];
            WordTempConfig config = new WordTempConfig(seats, bots, rounds, hintSeconds, level, 60, BLOCKED, tuning);
            WordTempEngine engine = new WordTempEngine(config, TABLE);
            boolean live = rng.nextDouble() < options.liveRate();
            boolean checkSecrets = m < options.secrecyMatches();
            long now = 1_000L + rng.nextInt(1_000_000);
            long startedAt = now;
            var started = engine.start(CandidateDealer.deal(BANK, seats, rounds, rng), rng.nextLong(), now);
            WordTempState state = started.newState();
            Mirror mirror = new Mirror(seats, tuning);
            mirror.apply(started.events(), config);
            Human[] humans = new Human[seats];
            for (int s = 0; s < seats; s++) {
                humans[s] = bots.contains(s) ? null
                        : new Human(0.6 + rng.nextDouble() * 0.8, 0.20 + rng.nextDouble() * 0.25);
            }
            WordTempInvariantChecker.check(state, config);
            ScoreJob job = null;
            boolean deserted = false;
            Set<Integer> botGuessersThisTurn = new HashSet<>();
            int guard = 0;

            while (!state.ended()) {
                if (++guard > 40_000) {
                    fail("match did not end: " + state);
                }
                for (int s = 0; s < seats; s++) {
                    if (humans[s] != null && state.alive(s)) {
                        refresh(humans[s], s, state, now, rng);
                    }
                }
                if (state.pendingHint() == null) {
                    job = null; // 무효·차례 끝으로 버려진 요청
                }
                long timerAt = now + engine.timer(state, now).orElseThrow();
                long scoreAt = job == null ? Long.MAX_VALUE : job.at();
                int actor = -1;
                long actorAt = Long.MAX_VALUE;
                for (int s : engine.pendingSeats(state)) {
                    assertThat(humans[s]).as("pending seat %d is a human", s).isNotNull();
                    if (humans[s].nextAt < actorAt) {
                        actorAt = humans[s].nextAt;
                        actor = s;
                    }
                }
                WordTempState before = state;
                List<WordTempEvent> events;
                if (actor >= 0 && actorAt <= scoreAt && actorAt < timerAt) {
                    long at = Math.max(now, actorAt);
                    phaseMs[state.phase().ordinal()] += at - now;
                    now = at;
                    Human h = humans[actor];
                    h.nextAt = Long.MAX_VALUE;
                    if (rng.nextDouble() < options.desertRate()) {
                        var d = engine.desert(state, actor, now);
                        assertThat(d.outcome()).isNotEqualTo(WordTempEngine.Desertion.Outcome.NOT_APPLICABLE);
                        events = d.events();
                        state = d.newState();
                        deserted = true;
                        assertThat(engine.desert(state, actor, now).outcome())
                                .isEqualTo(WordTempEngine.Desertion.Outcome.NOT_APPLICABLE);
                    } else {
                        WordTempAction action = decide(h, actor, state, tuning, now, rng);
                        if (action == null) {
                            continue;
                        }
                        try {
                            var r = engine.apply(state, actor, action, now, live);
                            events = r.events();
                            state = r.newState();
                            if (action instanceof WordTempAction.EndHints) {
                                endedEarly++;
                            }
                        } catch (WordTempActionRejectedException e) {
                            rejections++;
                            rejectionsBy.merge(e.reason(), 1, Integer::sum);
                            h.nextAt = now + 1_500 + rng.nextInt(2_500);
                            continue;
                        }
                    }
                } else if (scoreAt < timerAt) {
                    long at = Math.max(now, scoreAt);
                    phaseMs[state.phase().ordinal()] += at - now;
                    now = at;
                    boolean afterDeadline = now >= engine.deadline(state);
                    var r = engine.applyScore(state, job.hint(), job.result(), now);
                    events = r.events();
                    state = r.newState();
                    job = null;
                    if (afterDeadline) {
                        // 마감 뒤에 온 결과는 확정이든 무효든 그 자리에서 힌트 단계를 닫는다(§6.2)
                        assertThat(state.phase()).as("late live result closes the hint phase").isEqualTo(Phase.GUESS);
                        lateLiveClose++;
                    }
                } else {
                    phaseMs[state.phase().ordinal()] += timerAt - now;
                    now = timerAt;
                    if (rng.nextInt(50) == 0) {
                        // 낡은 TICK(다른 단계를 가리킴)은 아무것도 바꾸지 않는다
                        Phase other = state.phase() == Phase.GUESS ? Phase.HINT : Phase.GUESS;
                        assertThat(engine.apply(state, 0, new WordTempAction.Tick(state.turnNo(), state.attempt(),
                                other), now).newState()).isSameAs(state);
                    }
                    boolean pendingBefore = state.pendingHint() != null;
                    var r = rng.nextInt(4) == 0 && !pendingBefore && state.botPlan().isEmpty()
                            ? engine.apply(state, 0, new WordTempAction.Tick(state.turnNo(), state.attempt(),
                            state.phase()), now)
                            : engine.onTimer(state, now);
                    events = r.events();
                    state = r.newState();
                    assertThat(state).as("a due timer always moves the match").isNotEqualTo(before);
                    if (pendingBefore && events.stream().anyMatch(WordTempEvent.HintVoid.class::isInstance)) {
                        liveTimeout++;
                    }
                }
                transitions++;
                WordTempInvariantChecker.check(state, config);
                mirror.apply(events, config);
                mirror.assertMatches(WordTempViews.publicView(state, config, now));
                if (checkSecrets) {
                    assertNoSecrets(state, config, events, now);
                }

                // 집계 + 가짜 채점기
                for (WordTempEvent e : events) {
                    if (e instanceof WordTempEvent.Private p) {
                        assertThat(humans[p.seat()]).as("private event to a bot seat").isNotNull();
                        assertThat(before.alive(p.seat())).as("private event to an out seat").isTrue();
                    }
                    switch (e) {
                        case WordTempEvent.TurnStarted t -> botGuessersThisTurn.clear();
                        case WordTempEvent.HintPending p -> {
                            livePending++;
                            int floor = tuning.floor(state.attempt());
                            int roll = rng.nextInt(100);
                            // 표 밖 낱말의 온도(가정): 기준 −35° ~ 기준 +4° 에서 고르게 — 12.5% 가 기준 이상
                            LiveScore result = roll < 80 ? new LiveScore.Scored(Math.max(0, floor - 35 + rng.nextInt(40)))
                                    : roll < 92 ? new LiveScore.NotANoun() : new LiveScore.Failed();
                            // 5% 는 답이 오지 않는다 → 엔진 타이머가 8초 뒤 무효로
                            job = new ScoreJob(p.text(), rng.nextInt(20) == 0 ? Long.MAX_VALUE
                                    : now + 1_200 + rng.nextInt(3_000), result);
                        }
                        case WordTempEvent.HintScored h -> {
                            if (before.pendingHint() != null) {
                                liveScored++;
                                if (h.temp() >= tuning.floor(before.attempt())) {
                                    livePassed++;
                                    assertThat(state.phase()).as("a passing live score ends the hint phase")
                                            .isEqualTo(Phase.GUESS);
                                }
                            }
                        }
                        case WordTempEvent.HintVoid v -> liveVoid++;
                        case WordTempEvent.HintRevealed r -> {
                            int count = (int) state.hints().stream().filter(x -> x.attempt() == r.attempt()
                                    && x.status() == HintStatus.SCORED).count();
                            hintPhases[r.attempt()]++;
                            hintsGiven[r.attempt()] += count;
                            if (r.floorMet()) {
                                floorMet[r.attempt()]++;
                            }
                            if (r.text() == null) {
                                zeroHintPhases++;
                                assertThat(count).isZero();
                            }
                            if (count >= tuning.maxHints() && !r.floorMet()) {
                                fullHintPhases++;
                            }
                        }
                        case WordTempEvent.GuessesRevealed g -> {
                            attempts++;
                            boolean solved = g.guesses().stream().anyMatch(WordTempEvent.RevealedGuess::correct);
                            if (solved) {
                                solvedAt[g.attempt()]++;
                            } else {
                                allWrong++;
                                if (g.setterGain() == 0) {
                                    floorDenied++;
                                }
                            }
                            if (g.attempt() == lastAttempt) {
                                reachedLast++;
                            }
                            for (WordTempEvent.RevealedGuess guess : g.guesses()) {
                                if (bots.contains(guess.seat())) {
                                    int hit = guess.correct() ? 1 : 0;
                                    botGuessesBy[level.ordinal()]++;
                                    botHitsBy[level.ordinal()] += hit;
                                    botAttemptGuesses[level.ordinal() * 6 + g.attempt()]++;
                                    botAttemptHits[level.ordinal() * 6 + g.attempt()] += hit;
                                    botGuessersThisTurn.add(guess.seat());
                                }
                            }
                        }
                        case WordTempEvent.TurnEnded t -> {
                            turns++;
                            turnEnds.merge(t.reason(), 1, Integer::sum);
                            TurnSummary summary = state.history().get(state.history().size() - 1);
                            assertThat(summary.answer()).isEqualTo(t.answer());
                            if (t.reason() != TurnEndReason.SETTER_OUT) {
                                scoredTurns++;
                                setterGainPerRound += (double) summary.setterGain() / summary.round();
                                solverCount += summary.solvers().size();
                                for (int seat : botGuessersThisTurn) {
                                    botTurnsBy[level.ordinal()]++;
                                    botTurnHitsBy[level.ordinal()] += summary.solvers().contains(seat) ? 1 : 0;
                                }
                            }
                            botGuessersThisTurn.clear();
                        }
                        default -> {
                        }
                    }
                }
            }

            reasons.merge(state.result().endReason(), 1, Integer::sum);
            assertThat(engine.timer(state, now)).isEmpty();
            assertThat(engine.pendingSeats(state)).isEmpty();
            int top = 0;
            for (Standing st : state.result().standings()) {
                setterPoints += st.setterPoints();
                guessPoints += st.guessPoints();
                maxScore = Math.max(maxScore, st.total());
                if (!st.out()) {
                    top = Math.max(top, st.total());
                }
            }
            winnerPoints += top;
            draws += state.result().draw() ? 1 : 0;
            if (!deserted && state.result().endReason() == EndReason.FINISHED) {
                clean.add(now - startedAt);
                if (bots.isEmpty()) {
                    humanOnly.add(now - startedAt);
                }
                assertThat(state.history()).hasSize(seats * rounds);
            }
        }
        return new Stats(matches, reasons, transitions, rejections, rejectionsBy, turns, turnEnds, solvedAt,
                reachedLast, attempts, allWrong, floorDenied, hintPhases, floorMet, hintsGiven, zeroHintPhases,
                fullHintPhases, endedEarly, setterPoints, guessPoints, draws, winnerPoints,
                clean.stream().mapToLong(Long::longValue).toArray(),
                humanOnly.stream().mapToLong(Long::longValue).toArray(), phaseMs, livePending, liveScored, liveVoid,
                liveTimeout, livePassed, lateLiveClose, botGuessesBy, botHitsBy, botAttemptGuesses, botAttemptHits,
                botTurnsBy, botTurnHitsBy, scoredTurns, setterGainPerRound, solverCount, maxScore);
    }

    /** 단계가 바뀌었으면 이 사람의 다음 행동 시각을 새로 잡는다(입력에 걸리는 시간 — 가정). */
    private static void refresh(Human h, int seat, WordTempState state, long now, SplittableRandom rng) {
        long key = ((state.round() * 100L + state.turnNo()) * 10 + state.attempt()) * 10 + state.phase().ordinal();
        if (key == h.key) {
            return;
        }
        h.key = key;
        h.fumbled = false;
        switch (state.phase()) {
            // 8% 는 20초를 넘겨 무작위 배정을 받는다
            case WORD_SELECT -> h.nextAt = now + (rng.nextInt(100) < 8 ? 25_000 : 2_000 + rng.nextInt(13_000));
            case HINT -> {
                if (seat == state.setterSeat()) {
                    h.away = rng.nextInt(100) < 2;    // 2% 는 자리를 비운다 → 힌트 0개로 추리에 간다
                    h.willEnd = rng.nextInt(100) < 5; // 5% 는 3개 이상 낸 뒤 [힌트 끝]
                    h.nextAt = h.away ? Long.MAX_VALUE : now + typing(rng);
                } else {
                    h.nextAt = Long.MAX_VALUE;
                }
            }
            // 6% 는 30초를 넘겨 패스가 된다
            case GUESS -> h.nextAt = seat == state.setterSeat() ? Long.MAX_VALUE
                    : now + (rng.nextInt(100) < 6 ? 35_000 : 4_000 + rng.nextInt(22_000));
            default -> h.nextAt = Long.MAX_VALUE;
        }
    }

    /** 힌트 하나를 떠올려 치는 시간 — 5~14초. */
    private static long typing(SplittableRandom rng) {
        return 5_000 + rng.nextInt(9_001);
    }

    /** 사람 출제자의 다음 힌트 낱말(클래스 설명의 모형). */
    private static String nextHumanHint(Human h, WordTempState state, WordTempTuning tuning, SplittableRandom rng) {
        if (rng.nextInt(100) < 20) {
            return NONSENSE.get(2 + rng.nextInt(NONSENSE.size() - 2)); // 표 밖 낱말
        }
        int floor = tuning.floor(state.attempt());
        Set<String> used = new HashSet<>();
        state.hints().forEach(x -> used.add(x.text()));
        List<Neighbor> unused = LEGAL.get(state.answer().word()).stream().filter(n -> !used.contains(n.hint()))
                .toList();
        if (unused.isEmpty()) {
            return NONSENSE.get(2 + rng.nextInt(NONSENSE.size() - 2));
        }
        List<Neighbor> passing = unused.stream().filter(n -> n.temp() >= floor).toList();
        if (!passing.isEmpty() && rng.nextDouble() < h.setterSkill) {
            return passing.get(rng.nextInt(passing.size())).hint();
        }
        List<Neighbor> band = unused.stream().filter(n -> n.temp() < floor && n.temp() >= floor - 25).toList();
        List<Neighbor> from = band.isEmpty() ? unused : band;
        return from.get(rng.nextInt(from.size())).hint();
    }

    /** 이 사람이 지금 보낼 액션. 보낼 것이 없으면 null. */
    private static WordTempAction decide(Human h, int seat, WordTempState state, WordTempTuning tuning, long now,
                                         SplittableRandom rng) {
        switch (state.phase()) {
            case WORD_SELECT -> {
                Seat me = state.seat(seat);
                if (!h.fumbled && rng.nextInt(100) < 4) {
                    h.fumbled = true; // 범위 밖·이미 쓴 후보 → 거절
                    return new WordTempAction.SelectWord(state.round(),
                            me.used().isEmpty() ? me.candidates().size() : me.used().get(0));
                }
                List<Integer> open = new ArrayList<>();
                for (int i = 0; i < me.candidates().size(); i++) {
                    if (me.available(i)) {
                        open.add(i);
                    }
                }
                return new WordTempAction.SelectWord(state.round(), open.get(rng.nextInt(open.size())));
            }
            case HINT -> {
                if (state.pendingHint() != null) {
                    h.nextAt = now + 800; // 채점 중 — 가끔 성급하게 눌러 본다(거절)
                    return rng.nextInt(100) < 15 ? new WordTempAction.EndHints(state.turnNo(), state.attempt()) : null;
                }
                if (!h.fumbled && rng.nextInt(100) < 6) {
                    h.fumbled = true; // 잘못된 입력들 — 전부 거절, 개수는 그대로
                    String bad = switch (rng.nextInt(5)) {
                        case 0 -> "hint";
                        case 1 -> state.answer().word();
                        case 2 -> state.answer().word() + "들";
                        case 3 -> state.hints().isEmpty() ? "ㅋㅋ" : state.hints().get(0).text();
                        default -> "";
                    };
                    h.nextAt = now + 2_000;
                    return new WordTempAction.SubmitHint(state.turnNo(), state.attempt(), bad);
                }
                if (h.willEnd && state.hintCount() >= 3) {
                    return new WordTempAction.EndHints(state.turnNo(), state.attempt());
                }
                // 기준을 넘기거나 10개를 다 쓰거나 시간이 끝날 때까지 계속 낸다 — 넘겼는지는 서버가 알려 준다(단계가 끝난다)
                h.nextAt = now + typing(rng);
                return new WordTempAction.SubmitHint(state.turnNo(), state.attempt(),
                        " " + nextHumanHint(h, state, tuning, rng) + (rng.nextBoolean() ? "" : "!"));
            }
            case GUESS -> {
                if (!h.fumbled && rng.nextInt(100) < 3) {
                    h.fumbled = true;
                    h.nextAt = now + 1_500;
                    return new WordTempAction.SubmitGuess(state.turnNo(), state.attempt(), "answer?");
                }
                int hottest = -1;
                int shown = 0;
                for (Hint hint : state.hints()) {
                    if (hint.status() == HintStatus.SCORED) {
                        shown++;
                        hottest = Math.max(hottest, hint.temp());
                    }
                }
                String text;
                if (rng.nextDouble() < hitChance(hottest, shown, h.skill)) {
                    AnswerEntry answer = state.answer();
                    text = switch (rng.nextInt(4)) {
                        case 0 -> answer.word() + "요";
                        case 1 -> answer.aliases().isEmpty() ? answer.word() : answer.aliases().get(0);
                        case 2 -> " " + answer.word() + " !";
                        default -> answer.word();
                    };
                } else {
                    int roll = rng.nextInt(100);
                    text = roll < 15 ? ""
                            : roll < 20 ? "왕" + BLOCKED.iterator().next()
                            : roll < 40 ? BANK.get(rng.nextInt(BANK.size())).word() + "의"
                            : NONSENSE.get(2 + rng.nextInt(NONSENSE.size() - 2));
                }
                return new WordTempAction.SubmitGuess(state.turnNo(), state.attempt(), text);
            }
            default -> {
                return null;
            }
        }
    }

    // ---- 공개 이벤트만 듣는 클라 ----

    private static final class Mirror {
        final WordTempTuning tuning;
        Phase phase;
        int round;
        int turnNo;
        int setter = -1;
        int attempt;
        int hintCount;
        int revealed;
        boolean lastFloorMet;
        /** 공개 이벤트로 그린 이 출제 차례의 힌트 목록(D-320) — 공개 뷰의 {@code hints} 와 같아야 한다. */
        final List<HintView> hints = new ArrayList<>();
        final int[] totals;
        final boolean[] out;
        final boolean[] chosen;
        final Set<Integer> guessed = new HashSet<>();
        long seq;

        Mirror(int seats, WordTempTuning tuning) {
            this.tuning = tuning;
            totals = new int[seats];
            out = new boolean[seats];
            chosen = new boolean[seats];
        }

        private int countThisAttempt() {
            return (int) hints.stream().filter(h -> h.attempt() == attempt).count();
        }

        private int pendingIndex(String text) {
            for (int i = 0; i < hints.size(); i++) {
                if (hints.get(i).status() == HintStatus.PENDING && hints.get(i).text().equals(text)) {
                    return i;
                }
            }
            return -1;
        }

        void apply(List<WordTempEvent> events, WordTempConfig config) {
            for (WordTempEvent e : events) {
                if (e.isPrivate()) {
                    assertThat(e.sequenced()).isFalse();
                    assertThat(e).as("비공개는 후보와 내 추리뿐(D-320)").isInstanceOfAny(WordTempEvent.WordCandidates.class,
                            WordTempEvent.GuessAccepted.class);
                    continue;
                }
                assertThat(e.sequenced()).isTrue();
                seq++;
                switch (e) {
                    case WordTempEvent.WordSelectStarted s -> {
                        phase = Phase.WORD_SELECT;
                        round = s.round();
                        setter = -1;
                        attempt = 0;
                        hintCount = 0;
                        revealed = 0;
                        hints.clear();
                        Arrays.fill(chosen, false);
                        guessed.clear();
                        assertThat(s.remainingMs()).isEqualTo(config.tuning().selectMs());
                    }
                    case WordTempEvent.WordSelected s -> chosen[s.seat()] = true;
                    case WordTempEvent.TurnStarted t -> {
                        turnNo = t.turnNo();
                        round = t.round();
                        setter = t.setterSeat();
                        attempt = 1;
                        revealed = 0;
                        hints.clear();
                    }
                    case WordTempEvent.HintPhaseStarted h -> {
                        phase = Phase.HINT;
                        attempt = h.attempt();
                        hintCount = 0;
                        guessed.clear();
                    }
                    case WordTempEvent.HintCount h -> {
                        hintCount = h.count();
                        assertThat(h.count()).isBetween(0, h.max());
                        assertThat(h.max()).isEqualTo(tuning.maxHints());
                    }
                    case WordTempEvent.HintPending h -> {
                        assertThat(h.seat()).isEqualTo(setter);
                        hints.add(new HintView(h.text(), attempt, HintStatus.PENDING, -1));
                    }
                    case WordTempEvent.HintScored h -> {
                        assertThat(h.seat()).isEqualTo(setter);
                        assertThat(h.temp()).isBetween(0, 100);
                        int at = pendingIndex(h.text());
                        HintView scored = new HintView(h.text(), attempt, HintStatus.SCORED, h.temp());
                        if (at >= 0) {
                            hints.set(at, scored);
                        } else {
                            hints.add(scored);
                        }
                        assertThat(h.count()).isEqualTo(countThisAttempt());
                    }
                    case WordTempEvent.HintVoid h -> {
                        int at = pendingIndex(h.text());
                        assertThat(at).as("void of a hint that was announced pending").isNotNegative();
                        hints.remove(at);
                        assertThat(h.count()).isEqualTo(countThisAttempt());
                    }
                    case WordTempEvent.HintRevealed h -> {
                        revealed++;
                        // 차수 요약 = 공개된 그 차수의 확정 힌트 중 가장 높은 것(같으면 먼저 낸 것)
                        HintView best = null;
                        for (HintView v : hints) {
                            if (v.attempt() == h.attempt() && v.status() == HintStatus.SCORED
                                    && (best == null || v.temp() > best.temp())) {
                                best = v;
                            }
                        }
                        assertThat(h.attempt()).isEqualTo(attempt);
                        assertThat(h.text()).isEqualTo(best == null ? null : best.text());
                        assertThat(h.temp()).isEqualTo(best == null ? -1 : best.temp());
                        assertThat(h.floor()).isEqualTo(tuning.floor(h.attempt()));
                        assertThat(h.floorMet()).isEqualTo(h.temp() >= h.floor());
                        lastFloorMet = h.floorMet();
                    }
                    case WordTempEvent.GuessPhaseStarted g -> {
                        phase = Phase.GUESS;
                        guessed.clear();
                        assertThat(g.seats()).doesNotContain(setter);
                    }
                    case WordTempEvent.GuessSubmitted g -> assertThat(guessed.add(g.seat())).isTrue();
                    case WordTempEvent.GuessesRevealed g -> {
                        phase = Phase.ATTEMPT_RESULT;
                        guessed.clear();
                        int sum = 0;
                        for (WordTempEvent.ScoreChange c : g.scores()) {
                            totals[c.seat()] += c.delta();
                            sum += c.delta();
                            assertThat(totals[c.seat()]).as("점수 합 = 증감 합").isEqualTo(c.total());
                        }
                        long correct = g.guesses().stream().filter(WordTempEvent.RevealedGuess::correct).count();
                        assertThat(sum).isEqualTo(correct > 0 ? (int) correct * round : g.setterGain());
                        // D-320 — 출제자 점수는 전원 오답이고 그 차수가 기준을 넘겼을 때만 차수 × 라운드
                        assertThat(g.setterGain()).isEqualTo(correct == 0 && lastFloorMet ? g.attempt() * round : 0);
                    }
                    case WordTempEvent.TurnEnded t -> {
                        phase = Phase.TURN_END;
                        guessed.clear();
                        hints.removeIf(h -> h.status() == HintStatus.PENDING); // 채점 중이던 힌트는 버려진다
                        assertThat(t.hints().stream().map(i -> i.attempt() + ":" + i.text() + ":" + i.temp()).toList())
                                .as("차례 끝의 힌트 전체 = 그동안 공개된 확정 힌트")
                                .isEqualTo(hints.stream().map(v -> v.attempt() + ":" + v.text() + ":" + v.temp()).toList());
                        for (int s = 0; s < totals.length; s++) {
                            assertThat(t.totals().get(s)).isEqualTo(totals[s]);
                        }
                    }
                    case WordTempEvent.SeatOut s -> {
                        out[s.seat()] = true;
                        guessed.remove(s.seat());
                    }
                    case WordTempEvent.MatchEnded m -> {
                        phase = Phase.FINISHED;
                        guessed.clear();
                        for (Standing st : m.result().standings()) {
                            assertThat(st.total()).isEqualTo(totals[st.seat()]);
                            assertThat(st.out()).isEqualTo(out[st.seat()]);
                        }
                    }
                    default -> throw new AssertionError("private event leaked as public: " + e);
                }
            }
        }

        void assertMatches(PublicView view) {
            assertThat(view.phase()).isEqualTo(phase);
            assertThat(view.round()).isEqualTo(round);
            assertThat(view.turnNo()).isEqualTo(turnNo);
            if (phase != Phase.FINISHED) {
                assertThat(view.setterSeat()).isEqualTo(setter);
                assertThat(view.attempt()).isEqualTo(attempt);
                assertThat(view.hints()).as("공개 이벤트로 그린 힌트 목록 = 공개 뷰").isEqualTo(hints);
            }
            if (phase == Phase.HINT || phase == Phase.GUESS || phase == Phase.ATTEMPT_RESULT) {
                assertThat(view.hintCount()).isEqualTo(hintCount);
                assertThat(view.revealed()).hasSize(revealed);
            }
            if (phase == Phase.GUESS) {
                assertThat(Set.copyOf(view.guessedSeats())).isEqualTo(guessed);
            } else {
                assertThat(view.guessedSeats()).isEmpty();
            }
            for (SeatView s : view.seats()) {
                assertThat(s.total()).isEqualTo(totals[s.seat()]);
                assertThat(s.alive()).isEqualTo(!out[s.seat()]);
                if (phase != Phase.FINISHED) {
                    assertThat(s.chosen()).as("seat %d chosen", s.seat()).isEqualTo(chosen[s.seat()]);
                }
            }
        }
    }

    // ---- 은닉 ----

    /**
     * 공개 뷰·공개 이벤트 JSON 에 아직 공개되지 않은 낱말이 문자열 값으로 없다(§12·§17) — 후보(안 쓴 것은 끝까지), 진행 중인 정답과 같은 말,
     * 공개 전 추리, 가려진 답의 원문. 힌트는 비밀이 아니다(D-320): 이번 차례의 힌트는 공개 뷰에 낱말 그대로 <b>있어야</b> 한다 — 그래서 힌트와
     * 글자열이 같은 후보(진짜 표의 힌트 어휘에는 은행 낱말도 있다)는 "알려진 낱말"로 친다.
     */
    private static void assertNoSecrets(WordTempState state, WordTempConfig config, List<WordTempEvent> events,
                                        long now) {
        Set<String> shown = new HashSet<>();
        for (TurnSummary t : state.history()) {
            shown.add(t.answer());
            t.revealed().forEach(r -> shown.add(r.text()));
        }
        state.hints().forEach(h -> shown.add(h.text()));
        for (AttemptResult a : state.attempts()) {
            for (GuessEntry g : a.guesses()) {
                if (!g.pass() && !g.masked()) {
                    shown.add(g.text());
                }
            }
        }
        Set<String> secrets = new HashSet<>();
        for (Seat seat : state.seats()) {
            for (AnswerEntry c : seat.candidates()) {
                secrets.add(c.word());
                secrets.addAll(c.aliases());
            }
        }
        for (Guess g : state.guesses()) {
            if (!g.pass()) {
                secrets.add(g.text());
            }
        }
        for (AttemptResult a : state.attempts()) {
            for (GuessEntry g : a.guesses()) {
                if (g.masked()) {
                    secrets.add(g.text());
                }
            }
        }
        secrets.removeAll(shown);
        if (state.turnInProgress()) {
            // 진행 중인 정답은 힌트와 글자열이 같을 수 없다(힌트는 정답을 품지 못한다) — 힌트 공개가 정답을 흘리지 않는다
            assertThat(state.hints()).noneMatch(h -> h.text().equals(state.answer().word()));
        }
        StringBuilder out = new StringBuilder();
        try {
            String view = JSON.writeValueAsString(WordTempViews.publicView(state, config, now));
            out.append(view);
            assertThat(view).doesNotContain("candidates");
            if (state.turnInProgress()) {
                for (Hint h : state.hints()) {
                    if (h.status() != HintStatus.VOID) {
                        assertThat(view).as("public view shows hint %s", h.text()).contains("\"" + h.text() + "\"");
                    }
                }
            }
            for (WordTempEvent e : events) {
                if (!e.isPrivate()) {
                    out.append(JSON.writeValueAsString(e));
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        String published = out.toString();
        for (String secret : secrets) {
            if (published.contains("\"" + secret + "\"")) {
                fail("secret \"" + secret + "\" is in the public output during " + state.phase() + ": " + published);
            }
        }
    }
}
