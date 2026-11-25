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
import com.mirboard.domain.game.wordtemp.state.Phase;
import com.mirboard.domain.game.wordtemp.state.WordTempState;
import com.mirboard.domain.game.wordtemp.state.WordTempState.AttemptResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Guess;
import com.mirboard.domain.game.wordtemp.state.WordTempState.GuessEntry;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Hint;
import com.mirboard.domain.game.wordtemp.state.WordTempState.HintStatus;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Revealed;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnEndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.TurnSummary;
import com.mirboard.domain.game.wordtemp.state.WordTempViews;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.PublicView;
import com.mirboard.domain.game.wordtemp.state.WordTempViews.SeatView;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
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
 * 각 2,000판 모두 끝나고, 매 전이 뒤 불변식을 지키며, 공개 이벤트만 듣는 클라가 그린 판이 엔진의 공개 뷰와 같고, 공개 출력에 비밀 문자열이 없다.
 * 통계는 {@code server/build/wordtemp-sim-stats.txt}.
 *
 * <p><b>통계가 말해 주는 것의 한계</b>: 표는 합성({@link SyntheticWords})이고 사람은 모형이다 — 추리자가 맞힐 확률은 공개 힌트의 온도에서
 * {@link #hitChance} 로 어림하고, 입력에 걸리는 시간도 가정이다. 그래서 판 길이·점수 몫·전략 비교는 <b>상대적인 방향</b>(엉뚱한 힌트가 이득인가,
 * 최저 온도를 올리면 무엇이 달라지나)을 보는 재료이고, 절대값은 S4 시험판으로 맞춘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WordTempMatchSimulationTest {

    private static final int MATCHES = 2_000;
    private static final SyntheticWords WORDS = SyntheticWords.generate(0xC0FFEEL, 240, 3_000);
    private static final Set<String> BLOCKED = Set.of(WORDS.vocabulary.get(0), WORDS.vocabulary.get(1));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> REPORT = new TreeMap<>();

    /** 사람 출제자의 흉내 전략 — 공개될(가장 높은) 힌트를 어디에 두는가. */
    enum Strategy {
        /** 정직 — 60~85°. */
        HONEST,
        /** 줄타기 — 그 차수 최저 온도 바로 위(최저 ~ 최저+10°). */
        EDGE,
        /** 뜨겁게 — 85~95°. */
        HOT,
        /** 엉뚱한 힌트 하나 — 40° 미만 하나만 내고 [힌트 끝]. 최저 온도가 막으려는 꼼수. */
        OFF_TOPIC
    }

    /** 한 번의 실행(인원·판 길이 하나)의 집계. record 라 같은 씨앗의 재현 비교에 그대로 쓴다. */
    record Stats(int matches, Map<EndReason, Integer> reasons, int transitions, int rejections,
                 Map<RejectionReason, Integer> rejectionsBy, int turns, Map<TurnEndReason, Integer> turnEnds,
                 int[] solvedAtAttempt, int reachedThird, int attempts, int allWrongAttempts, int floorDenied,
                 int revealedBelowFloor, long setterPoints, long guessPoints, int draws, long winnerPoints,
                 long cleanMatches, long[] cleanDurations, long[] humanOnlyDurations, long[] phaseMs,
                 int livePending, int liveScored, int liveVoid, int liveTimeout, int botGuesses, int botHits,
                 int[] botGuessesBy, int[] botHitsBy,
                 double[] strategyGain, int[] strategyTurns, double[] strategyGuesserGain, int maxScore) {

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
                    + " " + turnEnds + " " + Arrays.toString(solvedAtAttempt) + " " + reachedThird + " " + attempts
                    + " " + allWrongAttempts + " " + floorDenied + " " + setterPoints + " " + guessPoints + " "
                    + draws + " " + Arrays.toString(cleanDurations).hashCode() + " " + Arrays.toString(phaseMs)
                    + " " + livePending + " " + liveScored + " " + liveVoid + " " + botGuesses + " " + botHits
                    + " " + Arrays.toString(strategyGain);
        }
    }

    /** 실행 조건. */
    record Options(double botRate, double desertRate, double liveRate, WordTempTuning tuning, int fixedHintSeconds,
                   int secrecyMatches) {

        static Options mixed() {
            return new Options(0.3, 0.002, 0.8, WordTempTuning.DEFAULT, 0, 25);
        }

        /** 사람만·탈주 없음·힌트 90초 — 전략 비교용. */
        static Options humansOnly(WordTempTuning tuning) {
            return new Options(0.0, 0.0, 0.8, tuning, 90, 0);
        }
    }

    // ---- 테스트 ----

    @ParameterizedTest(name = "{0}인 {1}라운드 2,000매치")
    @CsvSource({"3,1", "3,2", "3,3", "4,1", "4,2", "4,3", "5,1", "5,2", "5,3", "6,1", "6,2", "6,3"})
    @Timeout(600)
    @Order(1)
    void random_matches_always_end_and_keep_every_invariant(int seats, int rounds) {
        Stats s = run(seats, rounds, MATCHES, 0x77AA0000L + seats * 10L + rounds, Options.mixed());
        assertThat(s.matches()).isEqualTo(MATCHES);
        assertThat(s.reasons().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(MATCHES);
        assertThat(s.reasons().getOrDefault(EndReason.FINISHED, 0)).isGreaterThan(MATCHES * 8 / 10);
        // 모든 갈래가 실제로 밟혔다
        for (TurnEndReason reason : TurnEndReason.values()) {
            assertThat(s.turnEnds().getOrDefault(reason, 0)).as("turn end %s", reason).isPositive();
        }
        assertThat(s.solvedAtAttempt()[1]).isPositive();
        assertThat(s.solvedAtAttempt()[3]).isPositive();
        assertThat(s.floorDenied()).isPositive();
        assertThat(s.livePending()).isPositive();
        assertThat(s.liveScored()).isPositive();
        assertThat(s.liveVoid()).isPositive();
        assertThat(s.liveTimeout()).isPositive();
        assertThat(s.botGuesses()).isPositive();
        assertThat(s.rejections()).isPositive();
        assertThat(s.setterPoints()).isPositive();
        assertThat(s.guessPoints()).isPositive();
        // 한 판 최고점(§4): 출제 6r 씩 + 추리 r × (좌석 − 1)
        int cap = 0;
        for (int r = 1; r <= rounds; r++) {
            cap += 6 * r + r * (seats - 1);
        }
        assertThat(s.maxScore()).isLessThanOrEqualTo(cap);
        REPORT.put("1-" + seats + "-" + rounds, gridLine(seats, rounds, s));
    }

    @Test
    @Timeout(120)
    @Order(2)
    void the_same_seed_replays_the_same_matches() {
        assertThat(run(4, 2, 60, 99L, Options.mixed())).isEqualTo(run(4, 2, 60, 99L, Options.mixed()));
        assertThat(run(4, 2, 60, 99L, Options.mixed())).isNotEqualTo(run(4, 2, 60, 100L, Options.mixed()));
    }

    /**
     * 최저 온도의 값마다 출제 전략별 기대 점수 — "엉뚱한 힌트 하나"가 정직한 전략보다 낮아야 한다(설계서 §8 S1 완료 조건).
     * 최저 온도가 없으면(0·0·0) 엉뚱한 힌트가 가장 이득이라는 것도 같이 확인한다 — 최저 온도가 왜 있는지의 근거.
     */
    @Test
    @Timeout(600)
    @Order(3)
    void an_off_topic_hint_pays_less_than_an_honest_one_under_the_default_floors() {
        StringBuilder out = new StringBuilder();
        int[][] floorSets = {{0, 0, 0}, {40, 50, 60}, {50, 60, 70}, {60, 70, 80}};
        for (int[] floors : floorSets) {
            WordTempTuning tuning = WordTempTuning.DEFAULT.withFloors(floors[0], floors[1], floors[2]);
            Stats s = run(4, 2, 3_000, 0x51A7L, Options.humansOnly(tuning));
            double[] gain = new double[Strategy.values().length];
            double[] guesser = new double[Strategy.values().length];
            for (Strategy st : Strategy.values()) {
                gain[st.ordinal()] = s.strategyGain()[st.ordinal()] / s.strategyTurns()[st.ordinal()];
                guesser[st.ordinal()] = s.strategyGuesserGain()[st.ordinal()] / s.strategyTurns()[st.ordinal()];
            }
            out.append(String.format(Locale.ROOT,
                    "  최저 %2d·%2d·%2d | 정직 %.2f (추리자 합 %.2f) | 줄타기 %.2f (%.2f) | 뜨겁게 %.2f (%.2f) | 엉뚱 %.2f (%.2f)"
                            + " | 출제 몫 %4.1f%% | 3차까지 %4.1f%% | 4인 보통 %s%n",
                    floors[0], floors[1], floors[2], gain[0], guesser[0], gain[1], guesser[1], gain[2], guesser[2],
                    gain[3], guesser[3], 100.0 * s.setterPoints() / (s.setterPoints() + s.guessPoints()),
                    100.0 * s.reachedThird() / s.turns(), minutes(s.cleanDurations())));
            if (floors[0] == 0) {
                assertThat(gain[Strategy.OFF_TOPIC.ordinal()]).isGreaterThan(gain[Strategy.HONEST.ordinal()]);
            } else {
                assertThat(gain[Strategy.OFF_TOPIC.ordinal()]).isLessThan(gain[Strategy.HONEST.ordinal()]);
                assertThat(gain[Strategy.OFF_TOPIC.ordinal()]).isLessThan(0.3);
            }
            if (Arrays.equals(floors, new int[] {50, 60, 70})) {
                assertThat(tuning).isEqualTo(WordTempTuning.DEFAULT);
            }
        }
        REPORT.put("2-floors", out.toString());
    }

    @AfterAll
    static void writeStats() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("낱말 온도 시뮬레이션 (씨앗 고정 — 합성 표 ").append(WORDS.bank.size()).append(" 정답·힌트 어휘 ")
                .append(WORDS.vocabulary.size()).append(", 사람은 모형)\n")
                .append("한계: 추리자가 맞힐 확률·입력 시간은 가정이다(WordTempMatchSimulationTest.hitChance). 절대값이 아니라 방향을 본다.\n\n")
                .append("[1] 인원 × 판 길이 각 ").append(MATCHES)
                .append("판 — 봇 30% 섞음·힌트 제한 60/90/120/180(90 이 70%)·탈주 0.2%/행동·실시간 채점 켠 판 80%\n")
                .append("    판 길이 = 탈주 없이 끝까지 간 판의 분(평균 / 중앙 / 90% / 최대), 괄호는 사람만의 판 평균\n");
        REPORT.forEach((key, line) -> {
            if (key.startsWith("1-")) {
                out.append(line);
            }
        });
        out.append("\n[2] 최저 온도별 출제 전략의 기대 점수 — 사람 4명·2라운드·힌트 90초·3,000판, 출제 차례당 출제자 점수 ÷ 라운드 배수\n")
                .append("    정직 = 60~85° / 줄타기 = 최저~최저+10° / 뜨겁게 = 85~95° / 엉뚱 = 40° 미만 하나만. 괄호는 그 차례에 추리자들이 받은 합\n")
                .append(REPORT.getOrDefault("2-floors", "  (이 실행에서는 돌지 않음)\n"));
        Path file = Path.of("build", "wordtemp-sim-stats.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.print(out);
    }

    private static String gridLine(int seats, int rounds, Stats s) {
        long total = s.setterPoints() + s.guessPoints();
        long phaseTotal = Arrays.stream(s.phaseMs()).sum();
        StringBuilder phases = new StringBuilder();
        for (Phase p : List.of(Phase.WORD_SELECT, Phase.HINT, Phase.GUESS, Phase.ATTEMPT_RESULT, Phase.TURN_END)) {
            phases.append(String.format(Locale.ROOT, " %s %.0f%%", p, 100.0 * s.phaseMs()[p.ordinal()] / phaseTotal));
        }
        return String.format(Locale.ROOT,
                "  %d인 %d라운드 | 판 길이 %s (사람만 %s) | 끝 %s | 차례당 차수 %.2f · 3차까지 %.1f%% · 맞힘 1/2/3차 %.0f/%.0f/%.0f%%"
                        + " · 힌트 0개 %.1f%%%n"
                        + "              점수: 출제 몫 %.1f%% · 추리 몫 %.1f%% · 1등 평균 %.1f · 최고 %d · 무승부 %.1f%%"
                        + " | 최저 온도: 공개 힌트가 미달 %.1f%% · 전원 오답 차수 중 출제자 0점 %.1f%%%n"
                        + "              시간 몫:%s | 실시간 채점 %d(확정 %d·무효 %d 중 시간 초과 %d) | 봇 추리 적중(차수당) %.1f%% — 쉬움 %.1f · 보통 %.1f · 어려움 %.1f%%"
                        + " | 전이 %d · 거절 %d %s%n",
                seats, rounds, minutes(s.cleanDurations()), average(s.humanOnlyDurations()), s.reasons(),
                (double) s.attempts() / s.turns(), 100.0 * s.reachedThird() / s.turns(),
                100.0 * s.solvedAtAttempt()[1] / s.turns(), 100.0 * s.solvedAtAttempt()[2] / s.turns(),
                100.0 * s.solvedAtAttempt()[3] / s.turns(),
                100.0 * s.turnEnds().getOrDefault(TurnEndReason.NO_HINTS, 0) / s.turns(),
                100.0 * s.setterPoints() / total, 100.0 * s.guessPoints() / total,
                (double) s.winnerPoints() / s.matches(), s.maxScore(), 100.0 * s.draws() / s.matches(),
                100.0 * s.revealedBelowFloor() / Math.max(1, s.attempts()),
                100.0 * s.floorDenied() / Math.max(1, s.allWrongAttempts()), phases, s.livePending(), s.liveScored(),
                s.liveVoid(), s.liveTimeout(), 100.0 * s.botHits() / Math.max(1, s.botGuesses()),
                100.0 * s.botHitsBy()[0] / Math.max(1, s.botGuessesBy()[0]),
                100.0 * s.botHitsBy()[1] / Math.max(1, s.botGuessesBy()[1]),
                100.0 * s.botHitsBy()[2] / Math.max(1, s.botGuessesBy()[2]), s.transitions(),
                s.rejections(), s.rejectionsBy());
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
     * 추리자가 이번 차수에 맞힐 확률 — 공개된 힌트마다 그 온도로 "떠오를" 확률을 주고 합친다(가정).
     * 40° 미만은 거의 못 맞히고, 96° 이상이면 대개 맞힌다.
     */
    static double hitChance(List<Revealed> revealed, double skill) {
        double miss = 1.0;
        for (Revealed r : revealed) {
            int t = r.temp();
            double q = t < 40 ? 0.01 : t < 60 ? 0.04 : t < 75 ? 0.10 : t < 85 ? 0.22 : t < 96 ? 0.40 : 0.85;
            miss *= 1.0 - Math.min(0.95, q * skill);
        }
        return 1.0 - miss;
    }

    /** 사람 좌석 하나의 흉내. */
    private static final class Human {
        final double skill;
        long key = -1;
        long nextAt = Long.MAX_VALUE;
        List<String> plan = List.of();
        int planIndex;
        boolean willEnd;
        boolean fumbled;
        Strategy strategy;

        Human(double skill) {
            this.skill = skill;
        }
    }

    /** 가짜 실시간 채점기에 맡겨 둔 일. */
    private record ScoreJob(String hint, long at, LiveScore result) {
    }

    // ---- 시뮬레이터 ----

    private static Stats run(int seats, int rounds, int matches, long seed, Options options) {
        SplittableRandom rng = new SplittableRandom(seed);
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        Map<RejectionReason, Integer> rejectionsBy = new EnumMap<>(RejectionReason.class);
        Map<TurnEndReason, Integer> turnEnds = new EnumMap<>(TurnEndReason.class);
        int[] solvedAt = new int[4];
        long[] phaseMs = new long[Phase.values().length];
        List<Long> clean = new ArrayList<>();
        List<Long> humanOnly = new ArrayList<>();
        double[] strategyGain = new double[Strategy.values().length];
        double[] strategyGuesserGain = new double[Strategy.values().length];
        int[] strategyTurns = new int[Strategy.values().length];
        int transitions = 0;
        int rejections = 0;
        int turns = 0;
        int reachedThird = 0;
        int attempts = 0;
        int allWrong = 0;
        int floorDenied = 0;
        int belowFloor = 0;
        long setterPoints = 0;
        long guessPoints = 0;
        int draws = 0;
        long winnerPoints = 0;
        int livePending = 0;
        int liveScored = 0;
        int liveVoid = 0;
        int liveTimeout = 0;
        int botGuesses = 0;
        int botHits = 0;
        int[] botGuessesBy = new int[WordTempBotLevel.values().length];
        int[] botHitsBy = new int[WordTempBotLevel.values().length];
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
            WordTempConfig config = new WordTempConfig(seats, bots, rounds, hintSeconds, level, 60, BLOCKED,
                    options.tuning());
            WordTempEngine engine = new WordTempEngine(config, WORDS.table);
            boolean live = rng.nextDouble() < options.liveRate();
            boolean checkSecrets = m < options.secrecyMatches();
            long now = 1_000L + rng.nextInt(1_000_000);
            long startedAt = now;
            var started = engine.start(CandidateDealer.deal(WORDS.bank, seats, rounds, rng), rng.nextLong(), now);
            WordTempState state = started.newState();
            Mirror mirror = new Mirror(seats);
            mirror.apply(started.events(), state, config);
            Human[] humans = new Human[seats];
            for (int s = 0; s < seats; s++) {
                humans[s] = bots.contains(s) ? null : new Human(0.6 + rng.nextDouble() * 0.8);
            }
            WordTempInvariantChecker.check(state, config);
            ScoreJob job = null;
            boolean deserted = false;
            Strategy turnStrategy = null;
            int guard = 0;

            while (!state.ended()) {
                if (++guard > 20_000) {
                    fail("match did not end: " + state);
                }
                for (int s = 0; s < seats; s++) {
                    if (humans[s] != null && state.alive(s)) {
                        refresh(humans[s], s, state, engine, now, rng);
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
                        WordTempAction action = decide(h, actor, state, engine, now, rng);
                        if (action == null) {
                            continue;
                        }
                        try {
                            var r = engine.apply(state, actor, action, now, live);
                            events = r.events();
                            state = r.newState();
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
                    var r = engine.applyScore(state, job.hint(), job.result(), now);
                    events = r.events();
                    state = r.newState();
                    job = null;
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
                mirror.apply(events, state, config);
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
                        case WordTempEvent.TurnStarted t -> {
                            turnStrategy = humans[t.setterSeat()] == null ? null
                                    : (humans[t.setterSeat()].strategy = pickStrategy(rng));
                        }
                        case WordTempEvent.HintPending p -> {
                            livePending++;
                            int roll = rng.nextInt(100);
                            LiveScore result = roll < 80
                                    ? new LiveScore.Scored(rng.nextInt(50) == 0 ? 96 + rng.nextInt(8)
                                    : 5 + rng.nextInt(86))
                                    : roll < 92 ? new LiveScore.NotANoun() : new LiveScore.Failed();
                            // 5% 는 답이 오지 않는다 → 엔진 타이머가 8초 뒤 무효로
                            job = new ScoreJob(p.text(), rng.nextInt(20) == 0 ? Long.MAX_VALUE
                                    : now + 1_200 + rng.nextInt(3_000), result);
                        }
                        case WordTempEvent.HintScored h -> {
                            if (before.pendingHint() != null) {
                                liveScored++;
                            }
                        }
                        case WordTempEvent.HintVoid v -> liveVoid++;
                        case WordTempEvent.HintRevealed r -> {
                            if (r.text() != null && !r.floorMet()) {
                                belowFloor++;
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
                            if (g.attempt() == 3) {
                                reachedThird++;
                            }
                            for (WordTempEvent.RevealedGuess guess : g.guesses()) {
                                if (bots.contains(guess.seat())) {
                                    botGuesses++;
                                    botHits += guess.correct() ? 1 : 0;
                                    botGuessesBy[level.ordinal()]++;
                                    botHitsBy[level.ordinal()] += guess.correct() ? 1 : 0;
                                }
                            }
                        }
                        case WordTempEvent.TurnEnded t -> {
                            turns++;
                            turnEnds.merge(t.reason(), 1, Integer::sum);
                            TurnSummary summary = state.history().get(state.history().size() - 1);
                            assertThat(summary.answer()).isEqualTo(t.answer());
                            if (turnStrategy != null && t.reason() != TurnEndReason.SETTER_OUT) {
                                strategyTurns[turnStrategy.ordinal()]++;
                                strategyGain[turnStrategy.ordinal()] += (double) summary.setterGain() / summary.round();
                                strategyGuesserGain[turnStrategy.ordinal()] += summary.solvers().size();
                            }
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
                reachedThird, attempts, allWrong, floorDenied, belowFloor, setterPoints, guessPoints, draws,
                winnerPoints, clean.size(), clean.stream().mapToLong(Long::longValue).toArray(),
                humanOnly.stream().mapToLong(Long::longValue).toArray(), phaseMs, livePending, liveScored, liveVoid,
                liveTimeout, botGuesses, botHits, botGuessesBy, botHitsBy, strategyGain, strategyTurns,
                strategyGuesserGain, maxScore);
    }

    private static Strategy pickStrategy(SplittableRandom rng) {
        int roll = rng.nextInt(100);
        return roll < 40 ? Strategy.HONEST : roll < 65 ? Strategy.EDGE : roll < 80 ? Strategy.HOT : Strategy.OFF_TOPIC;
    }

    /** 단계가 바뀌었으면 이 사람의 다음 행동 시각을 새로 잡는다(입력에 걸리는 시간 — 가정). */
    private static void refresh(Human h, int seat, WordTempState state, WordTempEngine engine, long now,
                                SplittableRandom rng) {
        long key = ((state.round() * 100L + state.turnNo()) * 10 + state.attempt()) * 10 + state.phase().ordinal();
        if (key == h.key) {
            return;
        }
        h.key = key;
        h.fumbled = false;
        h.plan = List.of();
        h.planIndex = 0;
        switch (state.phase()) {
            // 8% 는 20초를 넘겨 무작위 배정을 받는다
            case WORD_SELECT -> h.nextAt = now + (rng.nextInt(100) < 8 ? 25_000 : 2_000 + rng.nextInt(13_000));
            case HINT -> {
                if (seat == state.setterSeat()) {
                    planHints(h, state, engine, rng);
                    h.nextAt = now + 5_000 + rng.nextInt(10_000);
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

    /** 이번 차수에 낼 힌트들 — 맨 앞이 전략의 띠에 드는 낱말, 나머지는 그보다 낮은 낱말(가끔 표 밖 낱말 → 실시간 채점). */
    private static void planHints(Human h, WordTempState state, WordTempEngine engine, SplittableRandom rng) {
        Strategy strategy = h.strategy == null ? Strategy.HONEST : h.strategy;
        int floor = engine.config().tuning().floor(state.attempt());
        int low;
        int high;
        switch (strategy) {
            case EDGE -> {
                low = floor;
                high = floor + 10;
            }
            case HOT -> {
                low = 85;
                high = 95;
            }
            case OFF_TOPIC -> {
                low = 5;
                high = 39;
            }
            default -> {
                low = 60;
                high = 85;
            }
        }
        Set<String> used = new HashSet<>();
        state.hints().forEach(x -> used.add(x.text()));
        List<Neighbor> usable = WORDS.table.neighbors(state.answer().word()).stream()
                .filter(n -> !used.contains(n.hint()) && HintRules.containment(state.answer(), n.hint()).isEmpty())
                .toList();
        List<Neighbor> band = usable.stream().filter(n -> n.temp() >= low && n.temp() <= high).toList();
        List<String> plan = new ArrayList<>();
        // 3% 는 아무것도 내지 않는다(자리를 비움) → 힌트 0개로 차례 끝
        if (rng.nextInt(100) >= 3 && !band.isEmpty()) {
            Neighbor main = band.get(rng.nextInt(band.size()));
            plan.add(main.hint());
            int extra = strategy == Strategy.OFF_TOPIC ? 0 : rng.nextInt(4);
            List<Neighbor> lower = usable.stream().filter(n -> n.temp() < main.temp() && n.temp() >= 15).toList();
            for (int i = 0; i < extra && !lower.isEmpty(); i++) {
                String hint = rng.nextInt(100) < 30
                        ? WORDS.vocabulary.get(rng.nextInt(WORDS.vocabulary.size())) // 표 밖일 가능성이 큰 낱말
                        : lower.get(rng.nextInt(lower.size())).hint();
                if (!plan.contains(hint)) {
                    plan.add(rng.nextInt(plan.size() + 1), hint);
                }
            }
        }
        h.plan = plan;
        h.planIndex = 0;
        h.willEnd = rng.nextInt(100) >= 7; // 7% 는 [힌트 끝]을 누르지 않고 시간을 다 쓴다
    }

    /** 이 사람이 지금 보낼 액션. 보낼 것이 없으면 null. */
    private static WordTempAction decide(Human h, int seat, WordTempState state, WordTempEngine engine, long now,
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
                if (h.planIndex < h.plan.size()) {
                    h.nextAt = now + 5_000 + rng.nextInt(9_000);
                    return new WordTempAction.SubmitHint(state.turnNo(), state.attempt(),
                            " " + h.plan.get(h.planIndex++) + (rng.nextBoolean() ? "" : "!"));
                }
                if (h.willEnd && state.hintCount() > 0) {
                    return new WordTempAction.EndHints(state.turnNo(), state.attempt());
                }
                return null;
            }
            case GUESS -> {
                if (!h.fumbled && rng.nextInt(100) < 3) {
                    h.fumbled = true;
                    h.nextAt = now + 1_500;
                    return new WordTempAction.SubmitGuess(state.turnNo(), state.attempt(), "answer?");
                }
                String text;
                if (rng.nextDouble() < hitChance(state.revealed(), h.skill)) {
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
                            : roll < 40 ? WORDS.bank.get(rng.nextInt(WORDS.bank.size())).word() + "의"
                            : WORDS.vocabulary.get(2 + rng.nextInt(WORDS.vocabulary.size() - 2));
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
        Phase phase;
        int round;
        int turnNo;
        int setter = -1;
        int attempt;
        int hintCount;
        int revealed;
        final int[] totals;
        final boolean[] out;
        final boolean[] chosen;
        final Set<Integer> guessed = new HashSet<>();
        long seq;

        Mirror(int seats) {
            totals = new int[seats];
            out = new boolean[seats];
            chosen = new boolean[seats];
        }

        void apply(List<WordTempEvent> events, WordTempState state, WordTempConfig config) {
            for (WordTempEvent e : events) {
                if (e.isPrivate()) {
                    assertThat(e.sequenced()).isFalse();
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
                    }
                    case WordTempEvent.HintRevealed h -> {
                        if (h.text() != null) {
                            revealed++;
                            assertThat(h.floorMet()).isEqualTo(h.temp() >= h.floor());
                        }
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
                        assertThat(g.setterGain()).isIn(0, g.attempt() * round);
                    }
                    case WordTempEvent.TurnEnded t -> {
                        phase = Phase.TURN_END;
                        guessed.clear();
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
     * 미공개 힌트, 공개 전 추리, 가려진 답의 원문.
     */
    private static void assertNoSecrets(WordTempState state, WordTempConfig config, List<WordTempEvent> events,
                                        long now) {
        Set<String> shown = new HashSet<>();
        for (TurnSummary t : state.history()) {
            shown.add(t.answer());
            t.revealed().forEach(r -> shown.add(r.text()));
        }
        state.revealed().forEach(r -> shown.add(r.text()));
        for (AttemptResult a : state.attempts()) {
            for (GuessEntry g : a.guesses()) {
                if (!g.pass() && !g.masked()) {
                    shown.add(g.text());
                }
            }
        }
        if (state.phase() == Phase.TURN_END || events.stream().anyMatch(WordTempEvent.TurnEnded.class::isInstance)) {
            for (Hint h : state.hints()) {
                if (h.status() == HintStatus.SCORED) {
                    shown.add(h.text());
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
        state.hints().forEach(h -> secrets.add(h.text()));
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
        StringBuilder out = new StringBuilder();
        try {
            out.append(JSON.writeValueAsString(WordTempViews.publicView(state, config, now)));
            assertThat(JSON.writeValueAsString(WordTempViews.publicView(state, config, now)))
                    .doesNotContain("candidates");
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
