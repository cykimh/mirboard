package com.mirboard.domain.game.starsquare.puzzle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * 퍼즐 생성 도구(`docs/rules-starsquare.md` §4.3) — 테스트 소스에 둔 오프라인 도구다. 운영 서버는 이것을 싣지 않는다.
 *
 * <p>무작위 마방진(3×3 8개 전부 / 4×4 7,040개 전부 / 5×5 무작위 탐색)에서 단서 칸을 고르고, 풀이기·줄 채우기로 잰 뒤
 * 띠 규칙({@link Rule})에 드는 것만 남긴다. 대칭 16가지로 같은 퍼즐은 하나로 친다(런타임이 대칭을 다시 입힌다).
 * 시드가 고정이라 같은 코드면 같은 자원이 나온다 — {@code PuzzleBankTest} 가 자원과 대조한다.
 *
 * <p>다시 만들기: {@code MIRBOARD_STARSQUARE_GENERATE=1 ./gradlew :server:test --rerun --tests "*.starsquare.puzzle.PuzzleGeneratorTool"}
 */
public final class PuzzleGenerator {

    /**
     * 띠 규칙 [수치] — 줄 채우기 비율(빈 칸 중 줄 채우기로 채워지는 몫)의 범위가 사람에게 느끼는 난이도의 자다(풀이기 노드 수는
     * 4×4·5×5 모두 수십 개라 사람 난이도를 가르지 못했다 — S1 측정).
     *
     * @param minFill 줄 채우기 비율 하한(이상)
     * @param maxFill 줄 채우기 비율 상한(이하)
     * @param target  남길 퍼즐 수(3×3 은 원본이 적어 전부)
     */
    public record Rule(Band band, double minFill, double maxFill, int target, long seed) {

        public String describe() {
            return String.format(Locale.ROOT, "clues=%d fill=[%.2f, %.2f] target=%d seed=%d",
                    band.clueCount(), minFill, maxFill, target, seed);
        }

        public boolean accepts(int fillable, int blanks) {
            double ratio = blanks == 0 ? 1.0 : (double) fillable / blanks;
            return ratio >= minFill - 1e-9 && ratio <= maxFill + 1e-9;
        }
    }

    public static final List<Rule> RULES = List.of(
            new Rule(Band.THREE_EASY, 0.0, 1.0, Integer.MAX_VALUE, 0x5A3E01L),
            new Rule(Band.FOUR_EASY, 1.0, 1.0, 300, 0x5A4E02L),
            new Rule(Band.FOUR_NORMAL, 0.3, 0.8, 300, 0x5A4A03L),
            new Rule(Band.FIVE_HARD, 0.25, 0.7, 300, 0x5A5D04L));

    public static Rule rule(Band band) {
        return RULES.stream().filter(r -> r.band() == band).findFirst().orElseThrow();
    }

    /** 규칙대로 퍼즐을 만든다(대칭으로 같은 것은 하나, 정렬된 순서). */
    public static List<PuzzleBank.Entry> generate(Rule rule) {
        Band band = rule.band();
        TreeMap<String, PuzzleBank.Entry> kept = new TreeMap<>();
        SplittableRandom rng = new SplittableRandom(rule.seed());
        if (band.size() == 3) {
            List<Grid> squares = new ArrayList<>();
            Solver.enumerate(3, squares::add);
            int center = 4;
            for (Grid square : squares) {
                for (int a = 0; a < 9; a++) {
                    for (int b = a + 1; b < 9; b++) {
                        if (a == center || b == center) {
                            continue; // 가운데는 늘 5 — 단서로 주면 낭비
                        }
                        int[] clues = new int[9];
                        clues[a] = square.get(a);
                        clues[b] = square.get(b);
                        consider(rule, Grid.of(clues), square, kept);
                    }
                }
            }
            return List.copyOf(kept.values());
        }
        List<Grid> fours = new ArrayList<>();
        if (band.size() == 4) {
            Solver.enumerate(4, fours::add);
        }
        int attempts = 0;
        while (kept.size() < rule.target()) {
            if (++attempts > 2_000_000) {
                throw new IllegalStateException("Band " + band.key() + " did not fill: " + kept.size());
            }
            Grid square = band.size() == 4
                    ? fours.get(rng.nextInt(fours.size()))
                    : Solver.random(5, rng, 200_000);
            consider(rule, randomClues(square, band.clueCount(), rng), square, kept);
        }
        return List.copyOf(kept.values());
    }

    private static void consider(Rule rule, Grid clues, Grid square, TreeMap<String, PuzzleBank.Entry> kept) {
        int blanks = clues.size() - clues.nonZeroCount();
        int fillable = Deduction.fillable(clues);
        if (!rule.accepts(fillable, blanks)) {
            return;
        }
        String key = canonicalKey(clues, square);
        if (kept.containsKey(key)) {
            return;
        }
        PuzzleBank.Entry entry = measure(clues, square);
        // 정답은 대칭 중 사전순으로 가장 작은 모양으로 둔다 — 같은 퍼즐이 늘 같은 줄로 저장되게.
        kept.put(key, canonicalEntry(entry));
    }

    /** 단서·정답을 재서 자원 한 줄로. */
    public static PuzzleBank.Entry measure(Grid clues, Grid solution) {
        Solver.Count all = Solver.count(clues, PuzzleBank.SOLUTION_CAP, 50_000_000L);
        Solver.Count first = Solver.count(clues, 1, 50_000_000L);
        return new PuzzleBank.Entry(clues.toArray(), solution.toArray(), all.solutions(), Deduction.fillable(clues),
                first.nodes());
    }

    private static Grid randomClues(Grid square, int count, SplittableRandom rng) {
        int nn = square.size();
        int[] idx = new int[nn];
        for (int i = 0; i < nn; i++) {
            idx[i] = i;
        }
        for (int i = nn - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = idx[i];
            idx[i] = idx[j];
            idx[j] = t;
        }
        int[] clues = new int[nn];
        for (int k = 0; k < count; k++) {
            clues[idx[k]] = square.get(idx[k]);
        }
        return Grid.of(clues);
    }

    /** 대칭 16가지 중 (단서, 정답)이 사전순으로 가장 작은 모양의 열쇠. */
    public static String canonicalKey(Grid clues, Grid solution) {
        String best = null;
        for (int s = 0; s < Symmetry.COUNT; s++) {
            Symmetry sym = new Symmetry(s);
            String key = encode(sym.apply(clues)) + "|" + encode(sym.apply(solution));
            if (best == null || key.compareTo(best) < 0) {
                best = key;
            }
        }
        return best;
    }

    private static PuzzleBank.Entry canonicalEntry(PuzzleBank.Entry entry) {
        Grid clues = Grid.of(entry.clues());
        Grid solution = Grid.of(entry.solution());
        String best = null;
        int bestIndex = 0;
        for (int s = 0; s < Symmetry.COUNT; s++) {
            Symmetry sym = new Symmetry(s);
            String key = encode(sym.apply(clues)) + "|" + encode(sym.apply(solution));
            if (best == null || key.compareTo(best) < 0) {
                best = key;
                bestIndex = s;
            }
        }
        Symmetry sym = new Symmetry(bestIndex);
        return new PuzzleBank.Entry(sym.apply(clues).toArray(), sym.apply(solution).toArray(), entry.solutions(),
                entry.fillable(), Solver.count(sym.apply(clues), 1, 50_000_000L).nodes());
    }

    private static String encode(Grid g) {
        StringBuilder sb = new StringBuilder(g.size() * 2);
        for (int i = 0; i < g.size(); i++) {
            sb.append((char) ('A' + g.get(i)));
        }
        return sb.toString();
    }

    /** 자원 파일 본문 — 퍼즐 한 줄씩(변경 diff 가 읽히게). */
    public static String toJson(Rule rule, List<PuzzleBank.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"band\": \"").append(rule.band().key()).append("\",\n");
        sb.append("  \"size\": ").append(rule.band().size()).append(",\n");
        sb.append("  \"clueCount\": ").append(rule.band().clueCount()).append(",\n");
        sb.append("  \"rule\": \"").append(rule.describe()).append("\",\n");
        sb.append("  \"puzzles\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            PuzzleBank.Entry e = entries.get(i);
            sb.append("    {\"clues\":").append(compact(e.clues()))
                    .append(",\"solution\":").append(compact(e.solution()))
                    .append(",\"solutions\":").append(e.solutions())
                    .append(",\"fillable\":").append(e.fillable())
                    .append(",\"nodes\":").append(e.nodes())
                    .append('}');
            sb.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String compact(int[] values) {
        return Arrays.toString(values).replace(" ", "");
    }
}
