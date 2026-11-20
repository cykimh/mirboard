package com.mirboard.domain.game.starsquare.puzzle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 자원 파일을 다시 쓰는 실행형 도구(`docs/rules-starsquare.md` §4.3). 보통 테스트 실행에서는 꺼져 있다.
 *
 * <p>{@code MIRBOARD_STARSQUARE_GENERATE=1 ./gradlew :server:test --rerun --tests "*.starsquare.puzzle.PuzzleGeneratorTool"}
 */
@EnabledIfEnvironmentVariable(named = "MIRBOARD_STARSQUARE_GENERATE", matches = "1")
class PuzzleGeneratorTool {

    @Test
    void write_resources() throws Exception {
        Path dir = Path.of("src", "main", "resources", "starsquare");
        Files.createDirectories(dir);
        for (PuzzleGenerator.Rule rule : PuzzleGenerator.RULES) {
            var entries = PuzzleGenerator.generate(rule);
            Path file = dir.resolve("puzzles-" + rule.band().key() + ".json");
            Files.writeString(file, PuzzleGenerator.toJson(rule, entries), StandardCharsets.UTF_8);
            System.out.printf("starsquare %s: %d puzzles -> %s%n", rule.band().key(), entries.size(), file);
        }
    }
}
