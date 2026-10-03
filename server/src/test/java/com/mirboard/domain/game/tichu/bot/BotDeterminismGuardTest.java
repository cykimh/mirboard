package com.mirboard.domain.game.tichu.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D-118 — 봇 패키지 메인 소스의 결정성 정적 가드.
 *
 * <p>{@code Card}·{@code Suit}·{@code Special} 은 enum/record 라 해시가 실행마다 달라질 수 있고,
 * {@code Set.of}/{@code Set.copyOf}/{@code Map.copyOf} 의 순회 순서는 JVM 실행마다 바뀌는
 * SALT 를 탄다. 그런 컬렉션을 <b>순회</b>하면 같은 시드에서도 봇이 다른 수를 둔다. 손으로
 * 조심하는 대신 토큰 자체를 금지한다(주석은 제외하고 검사). {@code contains} 조회는 순서와
 * 무관하므로 허용된다 — 상태 쪽 {@code ready}·{@code passedSeats} 는 그렇게만 쓴다.
 */
class BotDeterminismGuardTest {

    private static final Path BOT_SOURCES =
            Path.of("src/main/java/com/mirboard/domain/game/tichu/bot");

    private static final List<String> FORBIDDEN = List.of(
            "HashSet", "HashMap", "toSet(", "toMap(", "groupingBy(",
            "Set.of(", "Set.copyOf(", "Map.of(", "Map.copyOf(",
            "keySet()", "entrySet()", "ThreadLocalRandom", "currentTimeMillis", "nanoTime");

    @Test
    void bot_sources_avoid_hash_iteration_time_and_ambient_randomness() throws IOException {
        List<Path> sources = sources();
        assertThat(sources).as("봇 소스를 찾지 못함 — 작업 디렉터리 확인")
                .extracting(p -> p.getFileName().toString())
                .contains("HeuristicBotPolicy.java", "BotView.java", "HandPlanner.java",
                        "ComboFinder.java", "LegalActionEnumerator.java");

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String code = stripComments(Files.readString(source));
            for (String token : FORBIDDEN) {
                if (code.contains(token)) violations.add(source.getFileName() + ": " + token);
            }
            boolean randomAllowed = source.getFileName().toString().equals("RandomBotPolicy.java");
            if (!randomAllowed && code.contains("new Random")) {
                violations.add(source.getFileName() + ": new Random");
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(BOT_SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** 블록·줄 주석 제거. 문자열 리터럴 안의 주석 기호는 봇 소스에 없다는 전제. */
    static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
