package com.mirboard.domain.game.yacht.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.yacht.dice.Dice;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** `docs/rules-yacht.md` §5 표와 §5.2 겹치는 조합 예시. */
class ScoringTest {

    private static Dice dice(String faces) {
        String[] f = faces.trim().split("\\s+");
        return Dice.of(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                Integer.parseInt(f[3]), Integer.parseInt(f[4]));
    }

    @ParameterizedTest(name = "{0} → 포카드 {1} · 풀하우스 {2} · 스몰 {3} · 라지 {4} · 요트 {5}")
    @CsvSource({
            // §5.2 표 각 행
            "6 6 6 6 6, 30, 30, 0, 0, 50",
            "3 3 3 3 5, 17, 0, 0, 0, 0",
            "2 2 3 3 3, 0, 13, 0, 0, 0",
            "1 2 3 4 5, 0, 0, 15, 30, 0",
            "1 2 3 4 6, 0, 0, 15, 0, 0",
            "1 3 4 5 6, 0, 0, 15, 0, 0",
            "2 3 4 4 5, 0, 0, 15, 0, 0",
            "1 2 3 5 6, 0, 0, 0, 0, 0",
            // 순서와 무관
            "5 4 3 2 6, 0, 0, 15, 30, 0",
            "3 2 3 2 3, 0, 13, 0, 0, 0",
    })
    void special_categories(String faces, int four, int full, int small, int large, int yacht) {
        Dice d = dice(faces);
        assertThat(Scoring.points(Category.FOUR_OF_A_KIND, d)).isEqualTo(four);
        assertThat(Scoring.points(Category.FULL_HOUSE, d)).isEqualTo(full);
        assertThat(Scoring.points(Category.SMALL_STRAIGHT, d)).isEqualTo(small);
        assertThat(Scoring.points(Category.LARGE_STRAIGHT, d)).isEqualTo(large);
        assertThat(Scoring.points(Category.YACHT, d)).isEqualTo(yacht);
    }

    @Test
    void upper_categories_sum_their_face_and_choice_sums_everything() {
        Dice d = dice("3 3 4 1 3");
        assertThat(Scoring.points(Category.ONES, d)).isEqualTo(1);
        assertThat(Scoring.points(Category.TWOS, d)).isZero();
        assertThat(Scoring.points(Category.THREES, d)).isEqualTo(9);
        assertThat(Scoring.points(Category.FOURS, d)).isEqualTo(4);
        assertThat(Scoring.points(Category.SIXES, d)).isZero();
        assertThat(Scoring.points(Category.CHOICE, d)).isEqualTo(14);
    }

    @Test
    void candidates_cover_only_empty_categories_in_scorecard_order() {
        Scorecard card = Scorecard.EMPTY.with(Category.CHOICE, 20).with(Category.ONES, 3);
        Map<Category, Integer> c = Scoring.candidates(card, dice("6 6 6 2 2"));

        assertThat(c.keySet()).containsExactly(Category.TWOS, Category.THREES, Category.FOURS, Category.FIVES,
                Category.SIXES, Category.FOUR_OF_A_KIND, Category.FULL_HOUSE, Category.SMALL_STRAIGHT,
                Category.LARGE_STRAIGHT, Category.YACHT);
        assertThat(c).containsEntry(Category.SIXES, 18).containsEntry(Category.FULL_HOUSE, 22)
                .containsEntry(Category.YACHT, 0);
    }

    @Test
    void best_takes_the_highest_and_breaks_ties_by_upper_row() {
        // 1 2 3 4 5: 라지 30 이 가장 높다
        assertThat(Scoring.best(Scorecard.EMPTY, dice("1 2 3 4 5"))).isEqualTo(Category.LARGE_STRAIGHT);
        // 라지·초이스를 쓴 뒤에는 스몰 15 가 가장 높다
        Scorecard noLarge = Scorecard.EMPTY.with(Category.LARGE_STRAIGHT, 30).with(Category.CHOICE, 15);
        assertThat(Scoring.best(noLarge, dice("1 2 3 4 5"))).isEqualTo(Category.SMALL_STRAIGHT);
        // 남은 두 칸(라지·요트)이 모두 0 이면 위 칸인 라지
        Scorecard rest = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != Category.YACHT && c != Category.LARGE_STRAIGHT && !rest.filled(c)) {
                rest = rest.with(c, 0);
            }
        }
        assertThat(Scoring.best(rest, dice("1 1 2 2 4"))).isEqualTo(Category.LARGE_STRAIGHT);
        // 4 칸과 풀 하우스만 빈 칸: 4 4 4 2 2 → 4 칸 12 < 풀 하우스 16
        Scorecard tie = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != Category.FOURS && c != Category.FULL_HOUSE) {
                tie = tie.with(c, 0);
            }
        }
        assertThat(Scoring.best(tie, dice("4 4 4 2 2"))).isEqualTo(Category.FULL_HOUSE);
        Scorecard tie2 = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            if (c != Category.FOURS && c != Category.YACHT) {
                tie2 = tie2.with(c, 0);
            }
        }
        // 4 칸과 요트만 빈 칸: 1 2 3 5 6 → 둘 다 0 → 위 칸인 4 칸
        assertThat(Scoring.best(tie2, dice("1 2 3 5 6"))).isEqualTo(Category.FOURS);
    }

    @Test
    void best_on_a_full_card_is_an_error() {
        Scorecard full = Scorecard.EMPTY;
        for (Category c : Category.values()) {
            full = full.with(c, 0);
        }
        Scorecard f = full;
        assertThatThrownBy(() -> Scoring.best(f, dice("1 1 1 1 1"))).isInstanceOf(IllegalStateException.class);
    }
}
