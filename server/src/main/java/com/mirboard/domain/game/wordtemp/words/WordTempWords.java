package com.mirboard.domain.game.wordtemp.words;

import com.mirboard.domain.game.wordtemp.rules.WordNormalizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 낱말 자원 Bean(D-316) — 정답 은행·유사도 표·금칙어를 <b>기동 때 한 번</b> 읽는다(별자리 수판 {@code StarSquarePuzzles} 와 같은 때).
 * 하나라도 없거나 어긋나면 서버가 뜨지 않는 편이 낫다. 첫 판·{@code apply}(방 잠금 2초 근처)에서는 파싱하지 않는다 — 꺼내기만 한다.
 *
 * <p>은행의 낱말은 모두 표의 정답이어야 한다(표가 모르는 정답은 힌트를 하나도 매길 수 없다).
 */
@Component
public class WordTempWords {

    private static final Logger log = LoggerFactory.getLogger(WordTempWords.class);

    /** 지금 싣는 표. 새 표를 만들면 파일을 더하고 이 상수를 올린다(판 도중 바뀌어도 매긴 점수는 상태에 있다 — 룰 §6.3). */
    public static final String TABLE_RESOURCE = "wordtemp/table-v2.tsv.gz";
    public static final String BANK_RESOURCE = "wordtemp/bank.tsv";
    public static final String BLOCKED_RESOURCE = "wordtemp/blocked.txt";

    private final PackedSimilarityTable table;
    private final List<AnswerEntry> bank;
    private final Set<String> blockedWords;

    public WordTempWords() {
        long started = System.nanoTime();
        this.table = PackedSimilarityTable.fromClasspath(TABLE_RESOURCE);
        this.bank = readBank(BANK_RESOURCE);
        this.blockedWords = readBlocked(BLOCKED_RESOURCE);
        Set<String> known = Set.copyOf(table.answers());
        for (AnswerEntry entry : bank) {
            if (!known.contains(entry.word())) {
                throw new IllegalStateException("Bank word is not a table answer: " + entry.word());
            }
        }
        log.info("Word temperature words loaded: table={} (prompt {} · model {}) answers={} pairs={} vocabulary={}"
                        + " bank={} blocked={} ~{}KB heap, {}ms",
                table.version(), table.promptVersion(), table.model(), table.answers().size(), table.pairCount(),
                table.vocabularySize(), bank.size(), blockedWords.size(), table.estimatedBytes() / 1024,
                (System.nanoTime() - started) / 1_000_000);
    }

    public PackedSimilarityTable table() {
        return table;
    }

    /** 정답 은행(파일 순서). */
    public List<AnswerEntry> bank() {
        return bank;
    }

    /** 금칙어(부분 일치로 쓴다 — 룰 §12). */
    public Set<String> blockedWords() {
        return blockedWords;
    }

    /** {@code 낱말⇥범주⇥뜻풀이⇥같은 말(쉼표)} — {@code #} 줄은 건너뛴다. */
    static List<AnswerEntry> readBank(String resource) {
        List<AnswerEntry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines(resource)) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 3) {
                throw new IllegalStateException("Malformed bank line: " + line);
            }
            String word = cells[0].strip();
            if (WordNormalizer.normalize(word).filter(word::equals).isEmpty() || !seen.add(word)) {
                throw new IllegalStateException("Bank word is not a normalized unique word: " + word);
            }
            List<String> aliases = cells.length < 4 || cells[3].isBlank() ? List.of()
                    : Arrays.stream(cells[3].split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            out.add(new AnswerEntry(word, cells[1].strip(), cells[2].strip(), aliases));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Answer bank is empty: " + resource);
        }
        return List.copyOf(out);
    }

    static Set<String> readBlocked(String resource) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines(resource)) {
            out.add(line.strip());
        }
        return Set.copyOf(out);
    }

    private static List<String> lines(String resource) {
        try (InputStream in = WordTempWords.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Word temperature resource not found: " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && line.charAt(0) != '#') {
                    out.add(line);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }
}
