package com.mirboard.domain.game.starsquare.puzzle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 미리 만든 퍼즐 자원(`docs/rules-starsquare.md` §4.2) — 띠마다 {@code starsquare/puzzles-{띠}.json}. 서버는 퍼즐을 풀거나
 * 만들지 않고 여기서 꺼낸다. 자원은 테스트 소스의 생성 도구({@code PuzzleGenerator})가 만들고 {@code PuzzleBankTest} 가 전부 다시
 * 검증한다.
 */
public final class PuzzleBank {

    /**
     * 자원 파일의 퍼즐 한 줄.
     *
     * @param solutions 단서와 맞는 마방진 수(상한 {@link #SOLUTION_CAP} 에서 멈춤)
     * @param fillable  줄 채우기만으로 채워지는 빈 칸 수({@link Deduction#fillable})
     * @param nodes     풀이기가 첫 해까지 연 노드 수({@link Solver#count} cap 1)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(int[] clues, int[] solution, int solutions, int fillable, long nodes) {

        public PuzzleSpec toSpec(Band band) {
            return new PuzzleSpec(band, Grid.of(clues), Grid.of(solution));
        }
    }

    /** 자원 파일 하나. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BandFile(String band, int size, int clueCount, String rule, List<Entry> puzzles) {
    }

    /** 해 수를 세다 멈추는 상한. */
    public static final int SOLUTION_CAP = 100;

    private final Map<Band, List<PuzzleSpec>> byBand;

    private PuzzleBank(Map<Band, List<PuzzleSpec>> byBand) {
        this.byBand = byBand;
    }

    /** 클래스패스의 자원 네 개를 읽는다. 하나라도 없거나 비었으면 실패 — 서버가 뜨지 않는 편이 낫다. */
    public static PuzzleBank fromClasspath() {
        ObjectMapper mapper = new ObjectMapper();
        Map<Band, List<PuzzleSpec>> out = new EnumMap<>(Band.class);
        for (Band band : Band.values()) {
            BandFile file = readFile(mapper, band);
            List<PuzzleSpec> specs = file.puzzles().stream().map(e -> e.toSpec(band)).toList();
            if (specs.isEmpty()) {
                throw new IllegalStateException("Empty puzzle band " + band.key());
            }
            out.put(band, specs);
        }
        return new PuzzleBank(Collections.unmodifiableMap(out));
    }

    /** 테스트용 — 띠마다 퍼즐 목록을 직접 준다. */
    public static PuzzleBank of(Map<Band, List<PuzzleSpec>> byBand) {
        Map<Band, List<PuzzleSpec>> copy = new EnumMap<>(Band.class);
        byBand.forEach((band, list) -> copy.put(band, List.copyOf(list)));
        return new PuzzleBank(Collections.unmodifiableMap(copy));
    }

    public static BandFile readFile(ObjectMapper mapper, Band band) {
        try (InputStream in = PuzzleBank.class.getClassLoader().getResourceAsStream(band.resourcePath())) {
            if (in == null) {
                throw new IllegalStateException("Missing puzzle resource " + band.resourcePath());
            }
            return mapper.readValue(in, BandFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable puzzle resource " + band.resourcePath(), e);
        }
    }

    public List<PuzzleSpec> puzzles(Band band) {
        return byBand.getOrDefault(band, List.of());
    }
}
