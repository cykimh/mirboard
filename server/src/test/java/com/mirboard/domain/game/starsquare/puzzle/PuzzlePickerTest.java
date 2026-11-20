package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** `docs/rules-starsquare.md` §3-3·§4 — 매치 시작 때 고르는 R개. */
class PuzzlePickerTest {

    private final PuzzleBank bank = PuzzleBank.fromClasspath();

    @Test
    void easy_alternates_3x3_and_4x4_and_the_others_use_one_band() {
        List<PuzzleSpec> easy = PuzzlePicker.pick(bank, Difficulty.EASY, 5, new SplittableRandom(1));
        assertThat(easy).extracting(PuzzleSpec::band).containsExactly(
                Band.THREE_EASY, Band.FOUR_EASY, Band.THREE_EASY, Band.FOUR_EASY, Band.THREE_EASY);
        assertThat(PuzzlePicker.pick(bank, Difficulty.NORMAL, 3, new SplittableRandom(1)))
                .extracting(PuzzleSpec::band).containsOnly(Band.FOUR_NORMAL);
        assertThat(PuzzlePicker.pick(bank, Difficulty.HARD, 1, new SplittableRandom(1)))
                .extracting(PuzzleSpec::band).containsOnly(Band.FIVE_HARD);
    }

    @Test
    void picked_puzzles_are_transformed_but_still_consistent_and_not_repeated() {
        SplittableRandom rng = new SplittableRandom(77);
        for (int match = 0; match < 300; match++) {
            Difficulty d = Difficulty.values()[match % 3];
            List<PuzzleSpec> picked = PuzzlePicker.pick(bank, d, 5, rng);
            Set<String> originals = new HashSet<>();
            for (PuzzleSpec p : picked) {
                assertThat(MagicCheck.isMagic(p.solution(), p.clues())).isTrue();
                assertThat(p.clues().nonZeroCount()).isEqualTo(p.band().clueCount());
                assertThat(originals.add(PuzzleGenerator.canonicalKey(p.clues(), p.solution()))).isTrue();
            }
        }
    }

    @Test
    void the_same_seed_picks_the_same_puzzles() {
        List<PuzzleSpec> a = PuzzlePicker.pick(bank, Difficulty.HARD, 5, new SplittableRandom(123));
        List<PuzzleSpec> b = PuzzlePicker.pick(bank, Difficulty.HARD, 5, new SplittableRandom(123));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void difficulty_option_values_map_with_normal_as_the_default() {
        assertThat(Difficulty.fromOption("EASY")).isEqualTo(Difficulty.EASY);
        assertThat(Difficulty.fromOption("HARD")).isEqualTo(Difficulty.HARD);
        assertThat(Difficulty.fromOption(null)).isEqualTo(Difficulty.NORMAL);
        assertThat(Difficulty.fromOption("WHAT")).isEqualTo(Difficulty.NORMAL);
    }
}
