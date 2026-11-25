package com.mirboard.domain.game.wordtemp.words;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 운영 유사도 표(D-312·D-316, 설계서 §5.4) — 부팅 때 {@code table-vN.tsv.gz} 를 읽어 <b>정렬 배열</b>로 들고 있는다.
 * {@code Map<String, Map<String, Integer>>} 로 담지 않는다(힙): 힌트 어휘는 정렬된 {@code String[]} 하나(이진 탐색으로 번호), 정답마다
 * 오름차순 {@code int[]} 힌트 번호 + 같은 길이의 {@code byte[]} 온도. 조회는 이진 탐색 세 번이고 할당이 없다 — 방 잠금 안에서 불린다.
 *
 * <p>파일 형식(D-313): 첫 줄 {@code #wordtemp-table⇥version=…⇥promptVersion=…⇥model=…⇥created=…⇥answers=N⇥pairs=M}, 그다음 줄마다
 * {@code 정답⇥힌트:온도,힌트:온도,…}. 정답 줄과 줄 안의 힌트 모두 UTF-16 코드 단위 오름차순({@link String#compareTo} 와 같은 순서)이다.
 * 어긋난 파일은 읽다가 예외를 던진다 — 반쯤 읽힌 표로 서버가 뜨는 것보다 안 뜨는 편이 낫다.
 */
public final class PackedSimilarityTable implements SimilarityTable {

    private static final String MAGIC = "#wordtemp-table";

    private final Map<String, String> header;
    private final String[] vocab;
    private final String[] answers;
    private final int[][] hintIndex;
    private final byte[][] temps;
    private final List<String> answerList;
    private final int pairs;

    private PackedSimilarityTable(Map<String, String> header, String[] vocab, String[] answers, int[][] hintIndex,
                                  byte[][] temps, int pairs) {
        this.header = Map.copyOf(header);
        this.vocab = vocab;
        this.answers = answers;
        this.hintIndex = hintIndex;
        this.temps = temps;
        this.answerList = List.of(answers);
        this.pairs = pairs;
    }

    /** 클래스패스 자원(gzip)에서 읽는다. 없으면 예외. */
    public static PackedSimilarityTable fromClasspath(String resource) {
        try (InputStream in = PackedSimilarityTable.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Similarity table resource not found: " + resource);
            }
            return read(new GZIPInputStream(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read similarity table " + resource, e);
        }
    }

    /** 풀린(gzip 아님) 텍스트에서 읽는다 — 테스트가 작은 표를 만들 때도 쓴다. */
    public static PackedSimilarityTable read(InputStream text) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(text, StandardCharsets.UTF_8), 1 << 16);
        String first = reader.readLine();
        if (first == null || !first.startsWith(MAGIC)) {
            throw new IllegalStateException("Not a wordtemp table (missing header line)");
        }
        Map<String, String> header = new HashMap<>();
        for (String field : first.split("\t")) {
            int eq = field.indexOf('=');
            if (eq > 0) {
                header.put(field.substring(0, eq), field.substring(eq + 1));
            }
        }
        // 1차: 줄을 쪼개 두고 어휘를 모은다(파싱 중간물은 읽기가 끝나면 버려진다)
        List<String> answerWords = new ArrayList<>();
        List<String[]> rowHints = new ArrayList<>();
        List<byte[]> rowTemps = new ArrayList<>();
        TreeSet<String> words = new TreeSet<>();
        int pairs = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                throw new IllegalStateException("Malformed table line: " + abbreviate(line));
            }
            String answer = line.substring(0, tab);
            if (!answerWords.isEmpty() && answerWords.get(answerWords.size() - 1).compareTo(answer) >= 0) {
                throw new IllegalStateException("Table answers are not strictly ascending at: " + answer);
            }
            String[] cells = tab + 1 >= line.length() ? new String[0] : line.substring(tab + 1).split(",");
            String[] hints = new String[cells.length];
            byte[] temp = new byte[cells.length];
            for (int i = 0; i < cells.length; i++) {
                int colon = cells[i].lastIndexOf(':');
                if (colon <= 0) {
                    throw new IllegalStateException("Malformed table cell for " + answer + ": " + cells[i]);
                }
                hints[i] = cells[i].substring(0, colon);
                int t = Integer.parseInt(cells[i].substring(colon + 1));
                if (t < 0 || t > 100) {
                    throw new IllegalStateException("Temperature out of range for " + answer + "/" + hints[i] + ": " + t);
                }
                if (i > 0 && hints[i - 1].compareTo(hints[i]) >= 0) {
                    throw new IllegalStateException("Table hints are not strictly ascending for " + answer + " at "
                            + hints[i]);
                }
                temp[i] = (byte) t;
                words.add(hints[i]);
            }
            answerWords.add(answer);
            rowHints.add(hints);
            rowTemps.add(temp);
            pairs += cells.length;
        }
        // 2차: 어휘 번호로 바꾼다. 줄 안의 힌트가 어휘와 같은 순서라 번호도 오름차순이다.
        String[] vocab = words.toArray(new String[0]);
        int[][] index = new int[answerWords.size()][];
        for (int a = 0; a < index.length; a++) {
            String[] hints = rowHints.get(a);
            int[] row = new int[hints.length];
            for (int i = 0; i < hints.length; i++) {
                row[i] = Arrays.binarySearch(vocab, hints[i]);
            }
            index[a] = row;
        }
        PackedSimilarityTable table = new PackedSimilarityTable(header, vocab, answerWords.toArray(new String[0]),
                index, rowTemps.toArray(new byte[0][]), pairs);
        table.checkHeaderCounts();
        return table;
    }

    private void checkHeaderCounts() {
        String a = header.get("answers");
        String p = header.get("pairs");
        if (a != null && Integer.parseInt(a) != answers.length) {
            throw new IllegalStateException("Table header says answers=" + a + " but read " + answers.length);
        }
        if (p != null && Integer.parseInt(p) != pairs) {
            throw new IllegalStateException("Table header says pairs=" + p + " but read " + pairs);
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= 40 ? line : line.substring(0, 40) + "…";
    }

    @Override
    public OptionalInt lookup(String answer, String hint) {
        if (answer == null || hint == null) {
            return OptionalInt.empty();
        }
        int a = Arrays.binarySearch(answers, answer);
        if (a < 0) {
            return OptionalInt.empty();
        }
        int h = Arrays.binarySearch(vocab, hint);
        if (h < 0) {
            return OptionalInt.empty();
        }
        int at = Arrays.binarySearch(hintIndex[a], h);
        return at < 0 ? OptionalInt.empty() : OptionalInt.of(temps[a][at]);
    }

    /** 정답의 이웃 전부 — 파일의 순서(힌트 오름차순) 그대로. 부를 때마다 새 목록을 만든다(봇만 쓴다). */
    @Override
    public List<Neighbor> neighbors(String answer) {
        int a = answer == null ? -1 : Arrays.binarySearch(answers, answer);
        if (a < 0) {
            return List.of();
        }
        int[] row = hintIndex[a];
        List<Neighbor> out = new ArrayList<>(row.length);
        for (int i = 0; i < row.length; i++) {
            out.add(new Neighbor(vocab[row[i]], temps[a][i]));
        }
        return out;
    }

    @Override
    public List<String> answers() {
        return answerList;
    }

    /** 표 버전(첫 줄의 {@code version}) — 매치 기록에 남긴다. */
    public String version() {
        return header.getOrDefault("version", "unknown");
    }

    public String promptVersion() {
        return header.getOrDefault("promptVersion", "unknown");
    }

    public String model() {
        return header.getOrDefault("model", "unknown");
    }

    public int pairCount() {
        return pairs;
    }

    public int vocabularySize() {
        return vocab.length;
    }

    /**
     * 이 표가 붙잡는 힙의 어림(바이트) — 배열 머리 16, 참조 4(압축 참조), 한글 문자열은 글자당 2바이트로 센다. 실측은
     * {@code WordTempRealDataTest}.
     */
    public long estimatedBytes() {
        long bytes = 0;
        for (String word : vocab) {
            bytes += 24 + 16 + 2L * word.length() + 4;      // String + byte[] + 참조
        }
        for (int a = 0; a < answers.length; a++) {
            bytes += 16 + 4L * hintIndex[a].length + 16 + temps[a].length + 12; // int[] + byte[] + 참조 셋
        }
        return bytes;
    }
}
