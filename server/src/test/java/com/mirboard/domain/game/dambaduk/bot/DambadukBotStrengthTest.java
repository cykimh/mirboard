package com.mirboard.domain.game.dambaduk.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * 봇 3단계가 서로 갈리는가 (D-277). 점수 = 1위 단독 1, 공동 1위 1/공동 수, 나머지 0 의 평균 — 2인은 선을 번갈아, 3인은 한 단계 대 다른 단계
 * 둘(기준선 1/3)로 좌석·선을 돌린다. 결정적 봇끼리는 같은 판이 되풀이되므로 첫 두 수를 시드 무작위로 흔든다. 기본 실행은 작은 판 수,
 * {@code MIRBOARD_BOT_EVAL=1} 이면 판마다 200판이고 표를 {@code server/build/dambaduk-bot-strength.txt} 에 남긴다.
 */
class DambadukBotStrengthTest {

    private static final boolean FULL = "1".equals(System.getenv("MIRBOARD_BOT_EVAL"));
    private static final Map<String, String> TABLE = new TreeMap<>();

    private static int games(int small) {
        return FULL ? 200 : small;
    }

    private static double two(DambadukBotLevel a, DambadukBotLevel b, int small, long seed) {
        double r = BotMatches.winRate2(a, b, games(small), seed);
        TABLE.put("2인 " + name(a) + " 대 " + name(b), "%.3f (%d판)".formatted(r, games(small)));
        System.out.printf("dambaduk bot 2p %s vs %s: %.3f%n", name(a), name(b), r);
        return r;
    }

    private static double three(DambadukBotLevel a, DambadukBotLevel b, int small, long seed) {
        double r = BotMatches.winRate3(a, b, games(small), seed);
        TABLE.put("3인 " + name(a) + " 하나 대 " + name(b) + " 둘", "%.3f (%d판, 기준선 0.333)".formatted(r, games(small)));
        System.out.printf("dambaduk bot 3p %s vs %s x2: %.3f%n", name(a), name(b), r);
        return r;
    }

    private static String name(DambadukBotLevel l) {
        return l == null ? "무작위" : l.name();
    }

    @Test
    void two_players_easy_beats_random() {
        assertThat(two(DambadukBotLevel.EASY, null, 30, 1_000)).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void two_players_normal_beats_easy() {
        assertThat(two(DambadukBotLevel.NORMAL, DambadukBotLevel.EASY, 30, 2_000)).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void two_players_hard_beats_normal() {
        assertThat(two(DambadukBotLevel.HARD, DambadukBotLevel.NORMAL, 30, 3_000)).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void three_players_easy_beats_random() {
        assertThat(three(DambadukBotLevel.EASY, null, 30, 4_000)).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void three_players_normal_beats_easy() {
        assertThat(three(DambadukBotLevel.NORMAL, DambadukBotLevel.EASY, 30, 5_000)).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void three_players_hard_beats_normal() {
        assertThat(three(DambadukBotLevel.HARD, DambadukBotLevel.NORMAL, 90, 6_000)).isGreaterThanOrEqualTo(0.38);
    }

    @AfterAll
    static void writeTable() throws IOException {
        if (!FULL || TABLE.isEmpty()) {
            return;
        }
        StringBuilder b = new StringBuilder("담바둑 봇 강도(D-277, 시드 고정, 첫 두 수 무작위)\n");
        TABLE.forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        Path out = Path.of("build", "dambaduk-bot-strength.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, b.toString());
    }
}
