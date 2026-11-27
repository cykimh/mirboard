package com.mirboard.domain.game.wordtemp.words;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.WordTempTuning;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotPolicy;
import com.mirboard.domain.game.wordtemp.rules.CandidateDealer;
import com.mirboard.domain.game.wordtemp.rules.HintRules;
import com.mirboard.domain.game.wordtemp.rules.WordNormalizer;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * D-316 — <b>진짜</b> 자원(표 v2 — D-322·은행·금칙어)을 부팅 때와 같은 길로 읽어 본다. 형태 검증(설계서 §5.4), 은행 기준(D-313), 힙 실측,
 * 그리고 룰 v2(D-320)의 기준 온도를 진짜 표가 얼마나 받쳐 주는지(정답마다 기준 이상 힌트가 있는가)와 봇 출제 계획.
 */
class WordTempRealDataTest {

    private static final WordTempWords WORDS = new WordTempWords();

    @Test
    void the_table_and_bank_load_and_agree() {
        PackedSimilarityTable table = WORDS.table();
        assertThat(table.version()).isEqualTo("v2");
        assertThat(table.answers()).hasSize(351).isSorted();
        assertThat(table.pairCount()).isEqualTo(110_236);
        assertThat(WORDS.bank()).hasSize(351);
        assertThat(WORDS.bank().stream().map(AnswerEntry::word)).containsExactlyInAnyOrderElementsOf(table.answers());
        assertThat(WORDS.bank()).allSatisfy(e -> {
            assertThat(e.word().length()).isBetween(2, 6);
            assertThat(e.category()).isNotBlank();
            assertThat(e.gloss()).isNotBlank();
        });
        assertThat(WORDS.blockedWords()).isNotEmpty().allSatisfy(w -> assertThat(w).isNotBlank());
    }

    /** 배열 조회가 파일을 그대로 읽은 값과 같다 — 전 쌍. */
    @Test
    void every_pair_in_the_file_is_found_with_its_temperature() throws IOException {
        PackedSimilarityTable table = WORDS.table();
        int pairs = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getClassLoader().getResourceAsStream(WordTempWords.TABLE_RESOURCE)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] row = line.split("\t");
                for (String cell : row[1].split(",")) {
                    int colon = cell.lastIndexOf(':');
                    String hint = cell.substring(0, colon);
                    assertThat(table.lookup(row[0], hint)).as(row[0] + "/" + hint)
                            .hasValue(Integer.parseInt(cell.substring(colon + 1)));
                    pairs++;
                }
            }
        }
        assertThat(pairs).isEqualTo(table.pairCount());
        assertThat(table.lookup("바다", "없는낱말")).isEmpty();
    }

    /** 힌트는 룰 §5.1·§5.2 를 통과한 낱말이고(금칙어 없음), 정답마다 은행 기준(D-313: 96+ 0~5 · 75~95 3 이상 · 40~74 40 이상)을 채운다. */
    @Test
    void every_hint_is_a_legal_hint_and_every_answer_meets_the_bank_criteria() {
        PackedSimilarityTable table = WORDS.table();
        for (AnswerEntry answer : WORDS.bank()) {
            int near = 0;
            int hot = 0;
            int warm = 0;
            for (Neighbor n : table.neighbors(answer.word())) {
                assertThat(WordNormalizer.normalize(n.hint())).as(n.hint()).contains(n.hint());
                assertThat(HintRules.containment(answer, n.hint())).as(answer.word() + "/" + n.hint()).isEmpty();
                assertThat(WORDS.blockedWords()).as(n.hint()).noneMatch(n.hint()::contains);
                assertThat(n.temp()).isBetween(0, 100);
                if (n.temp() >= 96) {
                    near++;
                } else if (n.temp() >= 75) {
                    hot++;
                } else if (n.temp() >= 40) {
                    warm++;
                }
            }
            assertThat(near).as(answer.word() + " 96+").isBetween(0, 5);
            assertThat(hot).as(answer.word() + " 75~95").isGreaterThanOrEqualTo(3);
            assertThat(warm).as(answer.word() + " 40~74").isGreaterThanOrEqualTo(40);
        }
    }

    /** 이 정답에 낼 수 있는 표 이웃(정규화돼 있고 포함 규칙에 안 걸리는 것) — 봇 정책·사람 출제자가 쓸 수 있는 전부. */
    private static List<Neighbor> legalHints(PackedSimilarityTable table, AnswerEntry answer) {
        return table.neighbors(answer.word()).stream()
                .filter(n -> WordNormalizer.normalize(n.hint()).filter(n.hint()::equals).isPresent()
                        && HintRules.containment(answer, n.hint()).isEmpty()).toList();
    }

    /** 기준 온도들을 차례로, 서로 다른 힌트로 넘길 수 있는 차수 수 — 차수마다 "아직 안 쓴 것 중 기준 이상에서 가장 낮은 것"(탐욕, 최적). */
    private static int reachableAttempts(List<Neighbor> legal, int[] floors) {
        List<Integer> temps = new ArrayList<>(legal.stream().map(Neighbor::temp).sorted().toList());
        int reached = 0;
        for (int floor : floors) {
            int at = -1;
            for (int i = 0; i < temps.size(); i++) {
                if (temps.get(i) >= floor) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                break; // 오르는 기준이라 여기서 못 넘기면 뒤도 못 넘긴다
            }
            temps.remove(at);
            reached++;
        }
        return reached;
    }

    /**
     * D-320 의 근거 수치 — 진짜 표에서 정답마다 기준 이상 힌트가 있는가. 한 출제 차례 안에서는 힌트를 다시 낼 수 없으므로 "다섯 기준을 서로 다른
     * 힌트로 차례로 넘길 수 있는가"도 센다. 결과는 표준 출력과 {@code server/build/wordtemp-floor-stats.txt}.
     */
    @Test
    void the_real_table_supports_the_attempt_floors_for_most_answers() throws IOException {
        PackedSimilarityTable table = WORDS.table();
        int bank = WORDS.bank().size();
        StringBuilder out = new StringBuilder("낱말 온도 진짜 표의 기준 온도 통계 (D-320) — 은행 " + bank + "개, 표 " + table.version() + "\n");
        out.append("[a] 기준 이상인 합법 힌트가 하나 이상 있는 정답 수\n");
        for (int threshold : new int[] {70, 75, 80, 85, 88, 90}) {
            int count = 0;
            for (AnswerEntry answer : WORDS.bank()) {
                if (legalHints(table, answer).stream().anyMatch(n -> n.temp() >= threshold)) {
                    count++;
                }
            }
            out.append(String.format("    %d° 이상: %d / %d (없는 정답 %d)%n", threshold, count, bank, bank - count));
            assertThat(count).as(">= " + threshold).isPositive();
            if (threshold == 70) {
                assertThat(count).as("70° 는 모든 정답이 넘길 수 있다(은행 기준 75~95 3개 이상)").isEqualTo(bank);
            }
        }
        int five = 0;
        for (AnswerEntry answer : WORDS.bank()) {
            if (legalHints(table, answer).stream().filter(n -> n.temp() >= 70).count() >= 5) {
                five++;
            }
        }
        out.append(String.format("[b] 70° 이상인 서로 다른 합법 힌트가 5개 이상인 정답: %d / %d%n", five, bank));
        assertThat(five).isPositive();
        out.append("[c] 기준 세트별 — 서로 다른 힌트로 차례로 넘길 수 있는 차수 수(탐욕: 차수마다 안 쓴 것 중 기준 이상에서 가장 낮은 것)\n");
        int[][] sets = {{70, 75, 80, 85, 90}, {70, 75, 80, 85, 88}, {65, 70, 75, 80, 85}, {60, 65, 70, 75, 80},
                {70, 70, 75, 80, 85}, {50, 60, 70}};
        for (int[] floors : sets) {
            int[] byReached = new int[floors.length + 1];
            for (AnswerEntry answer : WORDS.bank()) {
                byReached[reachableAttempts(legalHints(table, answer), floors)]++;
            }
            StringBuilder dist = new StringBuilder();
            for (int k = 0; k <= floors.length; k++) {
                dist.append(String.format(" %d차까지 %d", k, byReached[k]));
            }
            out.append(String.format("    %-22s 전부 넘길 수 있는 정답 %d / %d (%.1f%%) |%s%n",
                    java.util.Arrays.toString(floors), byReached[floors.length], bank,
                    100.0 * byReached[floors.length] / bank, dist));
            assertThat(byReached[floors.length]).as(java.util.Arrays.toString(floors)).isPositive();
            assertThat(byReached[0]).as("첫 기준도 못 넘기는 정답은 없다 " + java.util.Arrays.toString(floors)).isZero();
        }
        Path file = Path.of("build", "wordtemp-floor-stats.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.print("[wordtemp] " + out);
    }

    /**
     * 봇 출제 계획(D-320)을 진짜 표로 — 모든 정답 × 강도 × 1~5차를 엔진처럼 걷는다(앞 차수에 낸 힌트는 {@code usedEarlier} 로 쌓인다).
     * 계획은 비지 않고, 힌트는 전부 합법이며, 안 쓴 힌트 중 기준 이상이 있으면 마지막 힌트가 기준을 넘기고(기준~기준+5° 에 있으면 그 안),
     * 없으면 안 쓴 것 중 가장 높은 것으로 끝난다. 그 앞의 힌트는 모두 기준 아래다(단계가 미리 끝나지 않는다).
     */
    @Test
    void the_setter_policy_walks_a_legal_plan_for_every_answer_level_and_attempt_on_the_real_table() {
        PackedSimilarityTable table = WORDS.table();
        WordTempBotPolicy policy = new WordTempBotPolicy(table);
        WordTempTuning tuning = WordTempTuning.DEFAULT;
        for (WordTempBotLevel level : WordTempBotLevel.values()) {
            int[] met = new int[tuning.maxAttempts() + 1];
            for (AnswerEntry answer : WORDS.bank()) {
                List<Neighbor> legal = legalHints(table, answer);
                Set<String> used = new HashSet<>();
                for (int attempt = 1; attempt <= tuning.maxAttempts(); attempt++) {
                    int floor = tuning.floor(attempt);
                    String at = level + " " + answer.word() + " attempt " + attempt;
                    List<Neighbor> unused = legal.stream().filter(n -> !used.contains(n.hint())).toList();
                    assertThat(unused).as(at).isNotEmpty();
                    long seed = answer.word().hashCode() * 31L + attempt;
                    List<String> given = new ArrayList<>();
                    int last = -1;
                    while (given.size() < tuning.maxHints()) {
                        Optional<String> hint = policy.nextHint(new WordTempBotPolicy.SetterView(answer, floor,
                                tuning.maxHints(), Set.copyOf(used), given.size(), level, seed));
                        if (hint.isEmpty()) {
                            break; // 계획을 다 냈다 — 엔진이 힌트 단계를 닫는다
                        }
                        assertThat(given).as(at).doesNotContain(hint.get());
                        assertThat(used).as(at).doesNotContain(hint.get());
                        assertThat(WordNormalizer.normalize(hint.get())).as(at).contains(hint.get());
                        assertThat(HintRules.containment(answer, hint.get())).as(at).isEmpty();
                        last = table.lookup(answer.word(), hint.get()).orElseThrow();
                        given.add(hint.get());
                        if (last >= floor) {
                            break; // 기준을 넘겼다 — 엔진이 그 자리에서 추리로 넘긴다
                        }
                    }
                    assertThat(given).as(at).isNotEmpty();
                    assertThat(given.size()).as(at).isLessThanOrEqualTo(level.warmMax() + 1);
                    int best = unused.stream().mapToInt(Neighbor::temp).max().orElseThrow();
                    if (best >= floor) {
                        assertThat(last).as(at).isGreaterThanOrEqualTo(floor);
                        if (unused.stream().anyMatch(n -> n.temp() >= floor
                                && n.temp() <= floor + WordTempBotLevel.PASS_BAND)) {
                            assertThat(last).as(at).isLessThanOrEqualTo(floor + WordTempBotLevel.PASS_BAND);
                        }
                        met[attempt]++;
                    } else {
                        assertThat(last).as(at + " — 넘길 수 없으면 아는 것 중 가장 높은 것").isEqualTo(best);
                    }
                    for (String earlier : given.subList(0, given.size() - 1)) {
                        assertThat(table.lookup(answer.word(), earlier).orElseThrow()).as(at).isLessThan(floor);
                    }
                    used.addAll(given);
                }
            }
            System.out.println("[wordtemp] bot " + level + " 출제 — 기준을 넘긴 정답 수(1~5차, 은행 " + WORDS.bank().size()
                    + "): " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(met, 1, met.length)));
            assertThat(met[1]).isEqualTo(WORDS.bank().size());
        }
    }

    /** 6인 × 길게(후보 4개씩 24개, 사람마다 서로 다른 범주)를 진짜 은행으로 나눌 수 있다 — 씨앗 200개. */
    @Test
    void the_real_bank_deals_candidates_for_six_seats_and_three_rounds() {
        for (long seed = 0; seed < 200; seed++) {
            List<List<AnswerEntry>> hands = CandidateDealer.deal(WORDS.bank(), 6, 3, new SplittableRandom(seed));
            assertThat(hands).hasSize(6).allSatisfy(hand -> {
                assertThat(hand).hasSize(4);
                assertThat(hand.stream().map(AnswerEntry::category).distinct()).hasSize(4);
            });
            List<String> all = new ArrayList<>();
            hands.forEach(hand -> hand.forEach(e -> all.add(e.word())));
            assertThat(all).doesNotHaveDuplicates();
        }
    }

    /**
     * 힙 실측(설계서 §5.4 — 목표 5MB 이하): 표·은행·금칙어를 한 벌 더 읽어 GC 뒤 쓰는 힙의 차이를 잰다. 결과는
     * {@code server/build/wordtemp-heap.txt} 에 남긴다.
     */
    @Test
    void loading_the_words_costs_well_under_five_megabytes_of_heap() throws IOException {
        long before = usedHeapAfterGc();
        WordTempWords extra = new WordTempWords();
        long after = usedHeapAfterGc();
        long delta = after - before;
        long estimate = extra.table().estimatedBytes();
        String report = String.format("낱말 온도 자원 힙 실측 (D-316)%n표 %s · 정답 %d · 쌍 %d · 어휘 %d · 은행 %d · 금칙어 %d%n"
                        + "GC 뒤 쓰는 힙 증가: %,d bytes (%.2f MB)%n배열 어림(estimatedBytes): %,d bytes (%.2f MB)%n",
                extra.table().version(), extra.table().answers().size(), extra.table().pairCount(),
                extra.table().vocabularySize(), extra.bank().size(), extra.blockedWords().size(),
                delta, delta / 1048576.0, estimate, estimate / 1048576.0);
        Path out = Path.of("build", "wordtemp-heap.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.print("[wordtemp] " + report);
        assertThat(delta).as("heap delta").isLessThan(5L * 1024 * 1024);
        assertThat(estimate).isLessThan(5L * 1024 * 1024);
        assertThat(extra.table().answers()).isNotEmpty(); // 재는 동안 살아 있게
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            used = Math.min(used, rt.totalMemory() - rt.freeMemory());
        }
        return used;
    }

    @Test
    void every_bank_category_has_a_korean_label_and_every_answer_has_a_gloss() {
        for (AnswerEntry answer : WORDS.bank()) {
            assertThat(AnswerCategory.known(answer.category())).as("범주 %s (%s)", answer.category(), answer.word()).isTrue();
            assertThat(AnswerCategory.label(answer.category())).matches("[가-힣·]+");
            assertThat(answer.gloss()).as("뜻풀이 %s", answer.word()).isNotBlank();
        }
    }
}
