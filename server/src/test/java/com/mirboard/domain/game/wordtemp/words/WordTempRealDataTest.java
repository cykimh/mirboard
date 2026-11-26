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
 * D-316 — <b>진짜</b> 자원(표 v1·은행·금칙어)을 부팅 때와 같은 길로 읽어 본다. 형태 검증(설계서 §5.4), 은행 기준(D-313), 힙 실측,
 * 그리고 봇 출제 띠가 모든 정답에서 후보를 갖는지(D-313 의 "쉬움 띠를 넓힐 것").
 */
class WordTempRealDataTest {

    private static final WordTempWords WORDS = new WordTempWords();

    @Test
    void the_table_and_bank_load_and_agree() {
        PackedSimilarityTable table = WORDS.table();
        assertThat(table.version()).isEqualTo("v1");
        assertThat(table.answers()).hasSize(324).isSorted();
        assertThat(table.pairCount()).isEqualTo(96_097);
        assertThat(WORDS.bank()).hasSize(324);
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

    /**
     * D-313 의 숙제 — 봇 출제 띠가 <b>모든 정답·모든 차수</b>에서 후보를 갖는다(띠가 비면 정책이 "띠에 가장 가까운 낱말"로 물러나 같은 힌트만
     * 낸다). 쉬움 띠 80~92 는 정답 50개에서 후보가 3개 미만이라 72~92 로 넓혔다(D-316) — 한 출제 차례가 3차수라 최소 3개가 필요하다.
     */
    @Test
    void every_bot_level_has_at_least_three_in_band_hints_for_every_answer_and_attempt() {
        PackedSimilarityTable table = WORDS.table();
        WordTempTuning tuning = WordTempTuning.DEFAULT;
        for (WordTempBotLevel level : WordTempBotLevel.values()) {
            int min = Integer.MAX_VALUE;
            for (AnswerEntry answer : WORDS.bank()) {
                for (int attempt = 1; attempt <= tuning.maxAttempts(); attempt++) {
                    int low = level.bandLow(tuning.floor(attempt));
                    int high = level.bandHigh(tuning.floor(attempt));
                    int inBand = (int) table.neighbors(answer.word()).stream()
                            .filter(n -> n.temp() >= low && n.temp() <= high && n.temp() < tuning.closeTemp()).count();
                    min = Math.min(min, inBand);
                    assertThat(inBand).as(level + " " + answer.word() + " attempt " + attempt + " band " + low + "~" + high)
                            .isGreaterThanOrEqualTo(3);
                }
            }
            System.out.println("[wordtemp] bot " + level + " 출제 띠 후보 최소 " + min + "개");
        }
    }

    /** 봇 정책이 진짜 표로 모든 정답에 합법인 힌트를 낸다(쉬움·보통·어려움 × 3차수). */
    @Test
    void the_setter_policy_finds_a_legal_hint_for_every_answer_on_the_real_table() {
        PackedSimilarityTable table = WORDS.table();
        WordTempBotPolicy policy = new WordTempBotPolicy(table);
        WordTempTuning tuning = WordTempTuning.DEFAULT;
        for (WordTempBotLevel level : WordTempBotLevel.values()) {
            for (AnswerEntry answer : WORDS.bank()) {
                Set<String> used = new HashSet<>();
                for (int attempt = 1; attempt <= tuning.maxAttempts(); attempt++) {
                    Optional<String> hint = policy.nextHint(new WordTempBotPolicy.SetterView(answer,
                            tuning.floor(attempt), tuning.closeTemp(), tuning.maxHints(), used, 0, level,
                            answer.word().hashCode() * 31L + attempt));
                    assertThat(hint).as(level + " " + answer.word() + " attempt " + attempt).isPresent();
                    int temp = table.lookup(answer.word(), hint.get()).orElseThrow();
                    assertThat(temp).isBetween(level.bandLow(tuning.floor(attempt)), level.bandHigh(tuning.floor(attempt)));
                    used.add(hint.get());
                }
            }
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
