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
 * `docs/rules-wordtemp.md` §11 — 봇 정책은 순수·결정적이고 표 안의 낱말만 쓴다. 출제는 목표 띠, 추리는 아는 은행 안의 역추적 + 상한.
 */
class WordTempBotPolicyTest {

    private static final SyntheticWords WORDS = SyntheticWords.generate(11L, 150, 3_000);
    private final WordTempBotPolicy policy = new WordTempBotPolicy(WORDS.table);

    private static SetterView setter(AnswerEntry answer, int attempt, WordTempBotLevel level, long seed) {
        return new SetterView(answer, WordTempTuning.DEFAULT.floor(attempt), 96, 5, Set.of(), 0, level, seed);
    }

    private int temp(AnswerEntry answer, String hint) {
        return WORDS.table.lookup(answer.word(), hint).orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(WordTempBotLevel.class)
    void the_setter_plans_table_hints_with_the_hottest_one_in_the_target_band(WordTempBotLevel level) {
        for (int i = 0; i < WORDS.bank.size(); i++) {
            AnswerEntry answer = WORDS.bank.get(i);
            for (int attempt = 1; attempt <= 3; attempt++) {
                int floor = WordTempTuning.DEFAULT.floor(attempt);
                List<String> plan = policy.hintPlan(setter(answer, attempt, level, i * 31L + attempt));
                assertThat(plan.size()).isBetween(1, level.maxHints());
                assertThat(plan).doesNotHaveDuplicates();
                int best = plan.stream().mapToInt(h -> temp(answer, h)).max().orElseThrow();
                assertThat(temp(answer, plan.get(0))).isEqualTo(best); // 맨 앞이 공개될 힌트
                assertThat(best).isBetween(level.bandLow(floor), level.bandHigh(floor)).isLessThan(96);
                for (String hint : plan) {
                    assertThat(HintRules.containment(answer, hint)).isEmpty();
                }
            }
        }
    }

    @Test
    void the_hard_setter_sits_just_above_the_floor_of_each_attempt() {
        assertThat(WordTempBotLevel.HARD.bandLow(50)).isEqualTo(50);
        assertThat(WordTempBotLevel.HARD.bandHigh(70)).isEqualTo(82);
        assertThat(WordTempBotLevel.EASY.bandLow(70)).isEqualTo(72);
        assertThat(WordTempBotLevel.NORMAL.bandHigh(50)).isEqualTo(85);
    }

    @Test
    void the_setter_walks_its_plan_one_hint_at_a_time_and_then_ends() {
        AnswerEntry answer = WORDS.bank.get(3);
        SetterView first = setter(answer, 2, WordTempBotLevel.HARD, 77L);
        List<String> plan = policy.hintPlan(first);
        for (int given = 0; given < plan.size(); given++) {
            assertThat(policy.nextHint(new SetterView(answer, 60, 96, 5, Set.of(), given, WordTempBotLevel.HARD, 77L)))
                    .contains(plan.get(given));
        }
        assertThat(policy.nextHint(new SetterView(answer, 60, 96, 5, Set.of(), plan.size(), WordTempBotLevel.HARD,
                77L))).isEmpty();
        // 같은 뷰·같은 씨앗이면 같은 계획, 다른 씨앗이면 (대개) 다른 계획
        assertThat(policy.hintPlan(first)).isEqualTo(plan);
    }

    @Test
    void the_setter_never_repeats_a_hint_from_an_earlier_attempt_and_avoids_ninety_six_plus() {
        AnswerEntry answer = WORDS.bank.get(5);
        Set<String> all = Set.copyOf(WORDS.table.neighbors(answer.word()).stream().map(Neighbor::hint).toList());
        Set<String> hot = Set.copyOf(WORDS.table.neighbors(answer.word()).stream().filter(n -> n.temp() >= 96)
                .map(Neighbor::hint).toList());
        for (long seed = 0; seed < 200; seed++) {
            List<String> first = policy.hintPlan(setter(answer, 1, WordTempBotLevel.NORMAL, seed));
            List<String> second = policy.hintPlan(new SetterView(answer, 60, 96, 5, Set.copyOf(first), 0,
                    WordTempBotLevel.NORMAL, seed + 1_000));
            assertThat(second).doesNotContainAnyElementsOf(first).doesNotContainAnyElementsOf(hot);
            assertThat(all).containsAll(second);
        }
    }

    @Test
    void a_setter_with_no_usable_neighbor_has_nothing_to_say() {
        WordTempBotPolicy small = new WordTempBotPolicy(WordTempFixtures.table());
        assertThat(small.nextHint(setter(AnswerEntry.of("우산", "생활"), 1, WordTempBotLevel.NORMAL, 1L)))
                .isEqualTo(Optional.empty());
        // 띠가 비면 띠에 가장 가까운 낱말(96 미만)
        assertThat(small.hintPlan(new SetterView(AnswerEntry.of("사과", "과일"), 50, 96, 5, Set.of("과일"), 0,
                WordTempBotLevel.EASY, 1L)).get(0)).isEqualTo("빨강");
        // 정답을 품은 이웃(수영 ← 수영복)은 표에 있어도 내지 않는다
        for (long seed = 0; seed < 50; seed++) {
            assertThat(small.hintPlan(setter(AnswerEntry.of("수영", "운동"), 1, WordTempBotLevel.EASY, seed)))
                    .doesNotContain("수영복").contains("물");
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
