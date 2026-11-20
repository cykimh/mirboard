package com.mirboard.domain.game.starsquare.puzzle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * `docs/rules-starsquare.md` §4·§15 — 자원 파일의 모든 퍼즐을 다시 검증한다: 정답이 마방진, 단서 ⊂ 정답, 단서 수가 띠 값, 해 수 ≥ 1,
 * 저장된 측정값 = 다시 잰 값, 띠 규칙 안, 대칭으로 겹치는 퍼즐 없음. 생성 도구가 같은 시드로 같은 자원을 다시 만드는지도 본다.
 * 띠 통계는 {@code server/build/starsquare-puzzles.txt}.
 */
class PuzzleBankTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void every_resource_puzzle_is_valid_and_its_measures_reproduce() {
        for (Band band : Band.values()) {
            PuzzleBank.BandFile file = PuzzleBank.readFile(mapper, band);
            PuzzleGenerator.Rule rule = PuzzleGenerator.rule(band);
            assertThat(file.band()).isEqualTo(band.key());
            assertThat(file.size()).isEqualTo(band.size());
            assertThat(file.clueCount()).isEqualTo(band.clueCount());
            assertThat(file.puzzles()).isNotEmpty();
            Set<String> keys = new HashSet<>();
            for (PuzzleBank.Entry e : file.puzzles()) {
                Grid clues = Grid.of(e.clues());
                Grid solution = Grid.of(e.solution());
                String where = band.key() + " " + clues;
                assertThat(clues.n()).as(where).isEqualTo(band.size());
                assertThat(MagicCheck.isMagic(solution)).as(where).isTrue();
                assertThat(clues.nonZeroCellsMatch(solution)).as(where).isTrue();
                assertThat(MagicCheck.isMagic(solution, clues)).as(where).isTrue();
                assertThat(clues.nonZeroCount()).as(where).isEqualTo(band.clueCount());
                PuzzleBank.Entry again = PuzzleGenerator.measure(clues, solution);
                assertThat(e.solutions()).as(where).isGreaterThanOrEqualTo(1).isEqualTo(again.solutions());
                assertThat(e.fillable()).as(where).isEqualTo(again.fillable());
                assertThat(e.nodes()).as(where).isEqualTo(again.nodes());
                assertThat(rule.accepts(e.fillable(), clues.size() - clues.nonZeroCount())).as(where).isTrue();
                assertThat(keys.add(PuzzleGenerator.canonicalKey(clues, solution))).as("duplicate " + where).isTrue();
            }
        }
    }

    @Test
    void bands_are_large_enough_for_a_long_match_without_repeats() {
        PuzzleBank bank = PuzzleBank.fromClasspath();
        // 길게(5) 쉬움은 3×3 세 개·4×4 두 개를 쓴다
        assertThat(bank.puzzles(Band.THREE_EASY)).hasSizeGreaterThanOrEqualTo(3);
        for (Band band : List.of(Band.FOUR_EASY, Band.FOUR_NORMAL, Band.FIVE_HARD)) {
            assertThat(bank.puzzles(band)).hasSizeBetween(200, 500);
        }
    }

    @Test
    void the_generator_reproduces_the_resources_and_writes_band_statistics() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("별자리 수판 퍼즐 은행 — docs/rules-starsquare.md §4 (PuzzleBankTest)\n");
        report.append("fill = 빈 칸 중 줄 채우기로 채워지는 비율, nodes = 풀이기가 첫 해까지 연 노드 수\n\n");
        for (PuzzleGenerator.Rule rule : PuzzleGenerator.RULES) {
            Band band = rule.band();
            List<PuzzleBank.Entry> generated = PuzzleGenerator.generate(rule);
            String expected = PuzzleGenerator.toJson(rule, generated);
            String actual = Files.readString(Path.of("src", "main", "resources", band.resourcePath()),
                    StandardCharsets.UTF_8);
            assertThat(actual).as("resource %s is stale — rerun PuzzleGeneratorTool", band.key()).isEqualTo(expected);
            report.append(statistics(rule, generated)).append('\n');
        }
        Path out = Path.of("build", "starsquare-puzzles.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
    }

    private static String statistics(PuzzleGenerator.Rule rule, List<PuzzleBank.Entry> entries) {
        Band band = rule.band();
        int blanks = band.size() * band.size() - band.clueCount();
        double[] fill = entries.stream().mapToDouble(e -> (double) e.fillable() / blanks).sorted().toArray();
        long[] nodes = entries.stream().mapToLong(PuzzleBank.Entry::nodes).sorted().toArray();
        int[] sols = entries.stream().mapToInt(PuzzleBank.Entry::solutions).sorted().toArray();
        long unique = Arrays.stream(sols).filter(s -> s == 1).count();
        Set<String> sources = new HashSet<>();
        for (PuzzleBank.Entry e : entries) {
            sources.add(Arrays.toString(e.solution()));
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "[%s] %d×%d, 단서 %d, 빈 칸 %d — %s", band.key(), band.size(), band.size(),
                band.clueCount(), blanks, rule.describe()));
        lines.add(String.format(Locale.ROOT, "  퍼즐 %d개(대칭 16가지로 겹치지 않음, 원본 마방진 %d개) — 해가 하나뿐 %d개, 해 수 최대 %d",
                entries.size(), sources.size(), unique, sols[sols.length - 1]));
        lines.add(String.format(Locale.ROOT, "  fill  min %.2f · p10 %.2f · 중앙 %.2f · p90 %.2f · max %.2f",
                fill[0], pct(fill, 10), pct(fill, 50), pct(fill, 90), fill[fill.length - 1]));
        lines.add(String.format(Locale.ROOT, "  nodes min %d · 중앙 %d · p90 %d · max %d",
                nodes[0], nodes[nodes.length / 2], nodes[Math.min(nodes.length - 1, nodes.length * 9 / 10)],
                nodes[nodes.length - 1]));
        return String.join("\n", lines) + "\n";
    }

    private static double pct(double[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, sorted.length * p / 100)];
    }
}
