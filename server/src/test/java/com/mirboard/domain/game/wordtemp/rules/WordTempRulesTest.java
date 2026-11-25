package com.mirboard.domain.game.wordtemp.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.wordtemp.action.RejectionReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.EndReason;
import com.mirboard.domain.game.wordtemp.state.WordTempState.MatchResult;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Seat;
import com.mirboard.domain.game.wordtemp.state.WordTempState.Standing;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * `docs/rules-wordtemp.md` §5.1(정규화)·§5.2(포함 규칙)·§7(정답 판정 표 전부)·§3.1(후보 나누기)·§8(순위).
 */
class WordTempRulesTest {

    // ---- §5.1 정규화 ----

    @ParameterizedTest
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "사과|사과", " 사 과 |사과", "사과!|사과", "사과…|사과", "'사과'|사과", "사·과|사과", "사과~~|사과",
            "눈|눈", "가나다라마바사아자차|가나다라마바사아자차", "　사과 |사과"})
    void normalizes_whitespace_and_punctuation(String raw, String expected) {
        assertThat(WordNormalizer.normalize(raw)).contains(expected);
    }

    @Test
    void composes_nfd_input_into_syllables() {
        String nfd = Normalizer.normalize("바다", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("바다");
        assertThat(WordNormalizer.normalize(nfd)).contains("바다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!?", "ㄱ", "ㅏ", "사ㄱ과", "apple", "사과a", "사과1", "사과🍎",
            "가나다라마바사아자차카", "사과+배"})
    void rejects_anything_that_is_not_one_to_ten_syllables(String raw) {
        assertThat(WordNormalizer.normalize(raw)).isEmpty();
    }

    @Test
    void null_is_not_a_word_and_blank_means_pass() {
        assertThat(WordNormalizer.normalize(null)).isEmpty();
        assertThat(WordNormalizer.blank(null)).isTrue();
        assertThat(WordNormalizer.blank(" \t… ")).isTrue();
        assertThat(WordNormalizer.blank("ㄱ")).isFalse();
        assertThat(WordNormalizer.blank("a")).isFalse();
    }

    // ---- §5.2 포함 규칙 ----

    @Test
    void a_hint_may_not_be_or_contain_the_answer_or_its_aliases() {
        AnswerEntry swim = AnswerEntry.of("수영", "운동");
        assertThat(HintRules.containment(swim, "수영")).contains(RejectionReason.HINT_IS_ANSWER);
        assertThat(HintRules.containment(swim, "수영장")).contains(RejectionReason.HINT_IS_ANSWER);
        assertThat(HintRules.containment(swim, "물놀이")).isEmpty();

        AnswerEntry phone = AnswerEntry.of("휴대폰", "물건", "핸드폰");
        assertThat(HintRules.containment(phone, "핸드폰")).contains(RejectionReason.HINT_IS_ANSWER);
        assertThat(HintRules.containment(phone, "핸드폰케이스")).contains(RejectionReason.HINT_IS_ANSWER);
    }

    @Test
    void a_hint_of_two_or_more_syllables_may_not_sit_inside_the_answer_but_one_syllable_may() {
        AnswerEntry snowman = AnswerEntry.of("눈사람", "겨울");
        assertThat(HintRules.containment(snowman, "사람")).contains(RejectionReason.HINT_INSIDE_ANSWER);
        assertThat(HintRules.containment(snowman, "눈사")).contains(RejectionReason.HINT_INSIDE_ANSWER);
        assertThat(HintRules.containment(snowman, "눈")).isEmpty();
        assertThat(HintRules.containment(snowman, "람")).isEmpty();
        assertThat(HintRules.containment(AnswerEntry.of("휴대폰", "물건", "핸드폰"), "핸드"))
                .contains(RejectionReason.HINT_INSIDE_ANSWER);
    }

    // ---- §7 정답 판정 표 ----

    @ParameterizedTest(name = "정답 {0} ← {1} = {2}")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "사과|사과|true", "사과| 사 과 |true", "사과|사과!|true",
            "사과|사과요|true", "사과|사과입니다|true", "사과|사과다|true", "사과|사과이다|true", "사과|사과임|true",
            "사과|사과이요|true", "사과|사과예요|true", "사과|사과이에요|true",
            "사과|사가|false", "사과|사괴|false",
            "사과|풋사과|false", "사과|사과나무|false",
            "바다|바다다|true", "바다|바다요|true",
            "수다|수|false", "수다|수다|true", "수다|수다다|true",
            "사과|사과요요|false", "사과|사과입니다요|false",
            "사과|요|false", "사과|다|false"})
    void judges_the_table_of_section_7(String answer, String guess, boolean expected) {
        Optional<String> normalized = WordNormalizer.normalize(guess);
        assertThat(normalized).isPresent();
        assertThat(AnswerJudge.correct(AnswerEntry.of(answer, ""), normalized.get())).isEqualTo(expected);
    }

    @Test
    void accepts_aliases_and_spelling_variants_only_when_the_bank_lists_them() {
        AnswerEntry phone = AnswerEntry.of("휴대폰", "물건", "핸드폰");
        assertThat(AnswerJudge.correct(phone, "핸드폰")).isTrue();
        assertThat(AnswerJudge.correct(phone, "핸드폰이에요")).isTrue();
        assertThat(AnswerJudge.correct(AnswerEntry.of("케이크", "음식", "케익"), "케익")).isTrue();
        assertThat(AnswerJudge.correct(AnswerEntry.of("케이크", "음식"), "케익")).isFalse();
        assertThat(AnswerJudge.correct(phone, "")).isFalse();
    }

    @Test
    void an_english_guess_is_a_format_error_not_a_wrong_answer() {
        assertThat(WordNormalizer.normalize("apple")).isEmpty();
    }

    // ---- §3.1 후보 나누기 ----

    @Test
    void candidate_count_is_max_of_three_and_rounds_plus_one() {
        assertThat(CandidateDealer.perSeat(1)).isEqualTo(3);
        assertThat(CandidateDealer.perSeat(2)).isEqualTo(3);
        assertThat(CandidateDealer.perSeat(3)).isEqualTo(4);
    }

    @Test
    void deals_distinct_categories_per_seat_and_no_word_or_alias_twice() {
        List<AnswerEntry> bank = new ArrayList<>();
        String[] categories = {"가", "나", "다", "라", "마", "바"};
        for (int c = 0; c < categories.length; c++) {
            for (int i = 0; i < 8; i++) {
                bank.add(AnswerEntry.of("" + (char) ('가' + c * 28) + (char) ('가' + i * 28 + 588), categories[c]));
            }
        }
        bank.add(AnswerEntry.of("휴대폰", "사", "핸드폰"));
        bank.add(AnswerEntry.of("핸드폰", "아", "휴대폰"));
        for (long seed = 0; seed < 200; seed++) {
            List<List<AnswerEntry>> hands = CandidateDealer.deal(bank, 6, 3, new SplittableRandom(seed));
            assertThat(hands).hasSize(6);
            Set<String> forms = new HashSet<>();
            for (List<AnswerEntry> hand : hands) {
                assertThat(hand).hasSize(4);
                assertThat(hand.stream().map(AnswerEntry::category).distinct().count()).isEqualTo(4);
                for (AnswerEntry e : hand) {
                    for (String form : AnswerForms.of(e)) {
                        assertThat(forms.add(form)).as("form dealt twice: %s", form).isTrue();
                    }
                }
            }
        }
        assertThat(CandidateDealer.deal(bank, 6, 3, new SplittableRandom(7)))
                .isEqualTo(CandidateDealer.deal(bank, 6, 3, new SplittableRandom(7)));
    }

    @Test
    void a_bank_that_is_too_small_fails_loudly() {
        List<AnswerEntry> bank = List.of(AnswerEntry.of("바다", "자연"), AnswerEntry.of("사과", "과일"));
        assertThatThrownBy(() -> CandidateDealer.deal(bank, 3, 1, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- §8 순위 ----

    private static Seat seat(boolean alive, int setter, int guess) {
        return new Seat(alive, setter, guess, List.of(AnswerEntry.of("바다", "자연")), List.of(), -1);
    }

    @Test
    void ties_share_a_rank_and_out_seats_are_all_last() {
        MatchResult r = MatchRanking.result(
                List.of(seat(true, 4, 2), seat(true, 6, 0), seat(false, 20, 5), seat(true, 1, 1), seat(false, 0, 0)),
                EndReason.FINISHED);
        assertThat(r.standings().stream().map(Standing::rank)).containsExactly(1, 1, 4, 3, 4);
        assertThat(r.standings().get(2).out()).isTrue();
        assertThat(r.standings().get(0).setterPoints()).isEqualTo(4);
        assertThat(r.standings().get(0).guessPoints()).isEqualTo(2);
        assertThat(r.draw()).isFalse();
    }

    @Test
    void everyone_level_with_nobody_out_is_a_draw() {
        assertThat(MatchRanking.result(List.of(seat(true, 2, 1), seat(true, 0, 3), seat(true, 3, 0)),
                EndReason.FINISHED).draw()).isTrue();
        assertThat(MatchRanking.result(List.of(seat(true, 2, 1), seat(true, 0, 3), seat(false, 3, 0)),
                EndReason.FINISHED).draw()).isFalse();
        assertThat(MatchRanking.result(List.of(seat(true, 2, 1), seat(true, 0, 3), seat(true, 3, 1)),
                EndReason.FINISHED).draw()).isFalse();
    }
}
