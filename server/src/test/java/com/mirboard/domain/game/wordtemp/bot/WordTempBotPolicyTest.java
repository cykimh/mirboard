package com.mirboard.domain.game.wordtemp.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.SyntheticWords;
import com.mirboard.domain.game.wordtemp.WordTempFixtures;
import com.mirboard.domain.game.wordtemp.WordTempTuning;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotPolicy.Clue;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotPolicy.GuesserView;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotPolicy.SetterView;
import com.mirboard.domain.game.wordtemp.rules.HintRules;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * `docs/rules-wordtemp.md` §11 — 봇 정책은 순수·결정적이고 표 안의 낱말만 쓴다. 출제(D-320)는 낮은 힌트 몇 개 뒤에 기준을 넘기는
 * 힌트 하나, 추리는 아는 은행 안의 역추적 + 상한.
 */
class WordTempBotPolicyTest {

    private static final SyntheticWords WORDS = SyntheticWords.generate(11L, 150, 3_000);
    private final WordTempBotPolicy policy = new WordTempBotPolicy(WORDS.table);

    private static SetterView setter(AnswerEntry answer, int attempt, WordTempBotLevel level, long seed) {
        return new SetterView(answer, WordTempTuning.DEFAULT.floor(attempt), 10, Set.of(), 0, level, seed);
    }

    private int temp(AnswerEntry answer, String hint) {
        return WORDS.table.lookup(answer.word(), hint).orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(WordTempBotLevel.class)
    void the_setter_plans_a_few_hints_below_the_floor_and_then_one_that_meets_it(WordTempBotLevel level) {
        for (int i = 0; i < WORDS.bank.size(); i++) {
            AnswerEntry answer = WORDS.bank.get(i);
            for (int attempt = 1; attempt <= 5; attempt++) {
                int floor = WordTempTuning.DEFAULT.floor(attempt);
                List<String> plan = policy.hintPlan(setter(answer, attempt, level, i * 31L + attempt));
                // 합성 표는 정답마다 96° 이상이 있고 기준 아래 띠도 넉넉하다 — 계획은 늘 "낮은 것 warm 개 + 넘기는 것 하나"
                assertThat(plan.size()).isBetween(level.warmMin() + 1, level.warmMax() + 1);
                assertThat(plan).doesNotHaveDuplicates();
                String main = plan.get(plan.size() - 1);
                assertThat(temp(answer, main)).as("마지막이 기준을 넘기는 힌트").isGreaterThanOrEqualTo(floor);
                boolean bandHasOne = WORDS.table.neighbors(answer.word()).stream()
                        .anyMatch(n -> n.temp() >= floor && n.temp() <= floor + WordTempBotLevel.PASS_BAND);
                if (bandHasOne) {
                    assertThat(temp(answer, main)).isLessThanOrEqualTo(floor + WordTempBotLevel.PASS_BAND);
                } else {
                    int lowestAbove = WORDS.table.neighbors(answer.word()).stream().mapToInt(Neighbor::temp)
                            .filter(t -> t >= floor).min().orElseThrow();
                    assertThat(temp(answer, main)).isEqualTo(lowestAbove);
                }
                for (String warm : plan.subList(0, plan.size() - 1)) {
                    // 먼저 내는 힌트는 기준 아래(그래야 단계가 끝나지 않는다), 너무 엉뚱하지 않게 기준 −30° 까지
                    assertThat(temp(answer, warm)).isBetween(floor - WordTempBotPolicy.WARM_RANGE, floor - 1);
                }
                for (String hint : plan) {
                    assertThat(HintRules.containment(answer, hint)).isEmpty();
                }
            }
        }
    }

    @Test
    void harder_setters_give_fewer_clues_before_the_passing_hint_and_harder_guessers_hit_more() {
        assertThat(WordTempBotLevel.EASY.warmMin()).isEqualTo(2);
        assertThat(WordTempBotLevel.EASY.warmMax()).isEqualTo(3);
        assertThat(WordTempBotLevel.NORMAL.warmMin()).isEqualTo(1);
        assertThat(WordTempBotLevel.NORMAL.warmMax()).isEqualTo(2);
        assertThat(WordTempBotLevel.HARD.warmMin()).isZero();
        assertThat(WordTempBotLevel.HARD.warmMax()).isEqualTo(1);
        assertThat(WordTempBotLevel.PASS_BAND).isEqualTo(5);
        assertThat(List.of(WordTempBotLevel.EASY.hitCapPct(), WordTempBotLevel.NORMAL.hitCapPct(),
                WordTempBotLevel.HARD.hitCapPct())).containsExactly(6, 14, 25);
        assertThat(List.of(WordTempBotLevel.EASY.knownPct(), WordTempBotLevel.NORMAL.knownPct(),
                WordTempBotLevel.HARD.knownPct())).containsExactly(30, 60, 100);
        assertThat(WordTempBotLevel.fromOption(null)).isEqualTo(WordTempBotLevel.NORMAL);
        assertThat(WordTempBotLevel.fromOption("없는값")).isEqualTo(WordTempBotLevel.NORMAL);
    }

    @Test
    void the_setter_walks_its_plan_one_hint_at_a_time_and_then_ends() {
        AnswerEntry answer = WORDS.bank.get(3);
        SetterView first = setter(answer, 2, WordTempBotLevel.NORMAL, 77L);
        List<String> plan = policy.hintPlan(first);
        for (int given = 0; given < plan.size(); given++) {
            assertThat(policy.nextHint(new SetterView(answer, 75, 10, Set.of(), given, WordTempBotLevel.NORMAL, 77L)))
                    .contains(plan.get(given));
        }
        assertThat(policy.nextHint(new SetterView(answer, 75, 10, Set.of(), plan.size(), WordTempBotLevel.NORMAL,
                77L))).isEmpty();
        // 같은 뷰·같은 씨앗이면 같은 계획
        assertThat(policy.hintPlan(first)).isEqualTo(plan);
        // 차수당 상한이 1 이면 넘기는 힌트 하나만, 0 이면 없다
        assertThat(policy.hintPlan(new SetterView(answer, 75, 1, Set.of(), 0, WordTempBotLevel.EASY, 77L))).hasSize(1);
        assertThat(policy.hintPlan(new SetterView(answer, 75, 0, Set.of(), 0, WordTempBotLevel.EASY, 77L))).isEmpty();
    }

    @Test
    void the_setter_never_repeats_a_hint_from_an_earlier_attempt() {
        AnswerEntry answer = WORDS.bank.get(5);
        Set<String> all = Set.copyOf(WORDS.table.neighbors(answer.word()).stream().map(Neighbor::hint).toList());
        for (long seed = 0; seed < 200; seed++) {
            List<String> first = policy.hintPlan(setter(answer, 1, WordTempBotLevel.EASY, seed));
            List<String> second = policy.hintPlan(new SetterView(answer, 75, 10, Set.copyOf(first), 0,
                    WordTempBotLevel.EASY, seed + 1_000));
            assertThat(second).doesNotContainAnyElementsOf(first);
            assertThat(all).containsAll(second);
            assertThat(temp(answer, second.get(second.size() - 1))).isGreaterThanOrEqualTo(75);
        }
    }

    @Test
    void a_setter_with_no_usable_neighbor_has_nothing_to_say_and_falls_back_when_the_band_is_empty() {
        WordTempBotPolicy small = new WordTempBotPolicy(WordTempFixtures.table());
        assertThat(small.nextHint(setter(AnswerEntry.of("우산", "생활"), 1, WordTempBotLevel.NORMAL, 1L)))
                .isEqualTo(Optional.empty());
        AnswerEntry apple = AnswerEntry.of("사과", "과일");     // 과일 88 · 빨강 70 · 나무 62 · 씨 55
        AnswerEntry snowman = AnswerEntry.of("눈사람", "겨울"); // 눈 90 · 겨울 80 · 당근 60 · 장갑 52
        for (long seed = 0; seed < 50; seed++) {
            // 띠(70~75°)에 있으면 그것
            assertThat(small.hintPlan(new SetterView(apple, 70, 10, Set.of(), 0, WordTempBotLevel.HARD, seed)))
                    .last().isEqualTo("빨강");
            // 띠가 비면 기준 이상 중 가장 낮은 것(겨울 80 — 눈 90 이 아니다)
            assertThat(small.hintPlan(new SetterView(snowman, 70, 10, Set.of(), 0, WordTempBotLevel.HARD, seed)))
                    .last().isEqualTo("겨울");
            // 기준 이상이 아예 없으면 아는 것 중 가장 높은 것을 마지막에 — 그 앞의 낮은 힌트는 그보다 낮다
            List<String> short90 = small.hintPlan(new SetterView(apple, 90, 10, Set.of(), 0, WordTempBotLevel.EASY, seed));
            assertThat(short90).last().isEqualTo("과일");
            assertThat(short90.subList(0, short90.size() - 1)).isSubsetOf("빨강", "나무").hasSize(2);
            // 앞 차수에 쓴 것은 빼고 고른다
            assertThat(small.hintPlan(new SetterView(apple, 90, 10, Set.of("과일"), 0, WordTempBotLevel.HARD, seed)))
                    .last().isEqualTo("빨강");
            // 정답을 품은 이웃(수영 ← 수영복 90)은 표에 있어도 내지 않는다 — 띠의 운동(70)이 넘기는 힌트
            assertThat(small.hintPlan(setter(AnswerEntry.of("수영", "운동"), 1, WordTempBotLevel.EASY, seed)))
                    .doesNotContain("수영복").last().isEqualTo("운동");
        }
    }

    private GuesserView guesser(AnswerEntry answer, int clues, WordTempBotLevel level, long seed) {
        List<Clue> shown = WORDS.table.neighbors(answer.word()).stream()
                .filter(n -> n.temp() >= 55 && n.temp() < 96).limit(clues)
                .map(n -> new Clue(n.hint(), n.temp())).toList();
        return new GuesserView(shown, Set.of(), level, seed,
                WordTempBotPolicy.mix(seed * 7 + clues, answer.word().hashCode()));
    }

    @Test
    void the_hard_guesser_ranks_the_real_answer_first_from_the_revealed_hints() {
        int top = 0;
        for (AnswerEntry answer : WORDS.bank) {
            List<String> ranked = policy.rank(guesser(answer, 2, WordTempBotLevel.HARD, 5L));
            if (!ranked.isEmpty() && ranked.get(0).equals(answer.word())) {
                top++;
            }
        }
        assertThat(top).isGreaterThan(WORDS.bank.size() * 9 / 10);
    }

    @ParameterizedTest
    @EnumSource(WordTempBotLevel.class)
    void the_guesser_knows_only_part_of_the_bank_and_its_hit_rate_stays_under_the_cap(WordTempBotLevel level) {
        int known = 0;
        int hits = 0;
        int trials = 0;
        for (long seed = 0; seed < 20; seed++) {
            for (AnswerEntry answer : WORDS.bank) {
                GuesserView view = guesser(answer, 2, level, seed);
                if (WordTempBotPolicy.knows(view, answer.word())) {
                    known++;
                }
                String guess = policy.guess(view);
                assertThat(guess.isEmpty() || WORDS.table.answers().contains(guess)).isTrue();
                if (guess.equals(answer.word())) {
                    hits++;
                }
                trials++;
            }
        }
        double knownRate = known / (double) trials;
        double hitRate = hits / (double) trials;
        assertThat(knownRate).isBetween(level.knownPct() / 100.0 - 0.05, level.knownPct() / 100.0 + 0.05);
        assertThat(hitRate).isLessThanOrEqualTo(level.hitCapPct() / 100.0 + 0.03);
        assertThat(hitRate).isPositive();
    }

    @Test
    void the_guesser_is_deterministic_never_repeats_a_ruled_out_word_and_passes_without_clues() {
        AnswerEntry answer = WORDS.bank.get(9);
        GuesserView view = guesser(answer, 3, WordTempBotLevel.HARD, 3L);
        assertThat(policy.guess(view)).isEqualTo(policy.guess(view));
        List<String> ranked = policy.rank(view);
        assertThat(ranked.get(0)).isEqualTo(answer.word());
        GuesserView without = new GuesserView(view.clues(), Set.of(answer.word()), view.level(),
                view.knowledgeSeed(), view.decisionSeed());
        assertThat(policy.rank(without)).doesNotContain(answer.word()).hasSize(ranked.size() - 1);
        assertThat(policy.guess(new GuesserView(List.of(), Set.of(), WordTempBotLevel.HARD, 1L, 2L))).isEmpty();
    }

    @Test
    void a_candidate_the_revealed_hint_could_not_legally_point_at_is_excluded() {
        // 힌트 "수영복" 은 정답 "수영" 을 품으므로 정답이 "수영" 일 수 없다
        WordTempBotPolicy small = new WordTempBotPolicy(WordTempFixtures.table());
        List<String> ranked = small.rank(new GuesserView(List.of(new Clue("수영복", 90)), Set.of(),
                WordTempBotLevel.HARD, 1L, 2L));
        assertThat(ranked).doesNotContain("수영").contains("바다");
    }
}
