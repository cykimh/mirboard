package com.mirboard.domain.game.yacht.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.yacht.dice.Dice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 6⁵ = 7,776 조합 × 12칸을 명세 표(`docs/rules-yacht.md` §5)에서 직접 쓴 기대값과 대조한다.
 *
 * <p>기대값은 같은 구현을 두 번 쓰지 않으려고 다른 방식으로 쓴다 — 개수를 내림차순으로 정렬한 "모양"과 정렬한 눈 문자열만 본다.
 */
class ScoringExhaustiveTest {

    private static int expected(Category c, int[] faces) {
        int[] counts = new int[7];
        int sum = 0;
        for (int f : faces) {
            counts[f]++;
            sum += f;
        }
        int[] shape = Arrays.stream(counts, 1, 7).filter(n -> n > 0).sorted().toArray();
        String distinct = Arrays.stream(faces).distinct().sorted()
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();
        return switch (c) {
            case ONES -> counts[1];
            case TWOS -> 2 * counts[2];
            case THREES -> 3 * counts[3];
            case FOURS -> 4 * counts[4];
            case FIVES -> 5 * counts[5];
            case SIXES -> 6 * counts[6];
            case CHOICE -> sum;
            case FOUR_OF_A_KIND -> shape[shape.length - 1] >= 4 ? sum : 0;
            case FULL_HOUSE -> Arrays.equals(shape, new int[] {2, 3}) || Arrays.equals(shape, new int[] {5}) ? sum : 0;
            case SMALL_STRAIGHT -> distinct.contains("1234") || distinct.contains("2345") || distinct.contains("3456")
                    ? 15 : 0;
            case LARGE_STRAIGHT -> distinct.equals("12345") || distinct.equals("23456") ? 30 : 0;
            case YACHT -> shape.length == 1 ? 50 : 0;
        };
    }

    @Test
    void every_roll_matches_the_spec_in_every_category() {
        int checked = 0;
        List<String> mismatches = new ArrayList<>();
        for (int a = 1; a <= 6; a++)
            for (int b = 1; b <= 6; b++)
                for (int c = 1; c <= 6; c++)
                    for (int d = 1; d <= 6; d++)
                        for (int e = 1; e <= 6; e++) {
                            int[] faces = {a, b, c, d, e};
                            Dice dice = Dice.of(a, b, c, d, e);
                            for (Category cat : Category.values()) {
                                int want = expected(cat, faces);
                                int got = Scoring.points(cat, dice);
                                if (want != got) {
                                    mismatches.add(Arrays.toString(faces) + " " + cat + " want=" + want + " got=" + got);
                                }
                                checked++;
                            }
                        }
        assertThat(mismatches).isEmpty();
        assertThat(checked).isEqualTo(7_776 * 12);
    }
}
