package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.bot.BotMatchHarness.Policy;
import com.mirboard.domain.game.skullking.bot.BotMatchHarness.Stat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-230 — 스컬킹 봇 <b>대형 평가</b> (선택 실행, Docker 불필요). 필수 강도 테스트
 * ({@link SkullKingBotStrengthTest})보다 판 수를 늘려 인원별 승률·평균 점수 표와 한 수 결정 시간을
 * 낸다. `docs/rules-skullking.md` §16.6 의 표가 이 출력이다.
 *
 * <pre>
 * MIRBOARD_BOT_EVAL=1 ./gradlew :server:test --rerun \
 *     --tests "com.mirboard.domain.game.skullking.bot.SkullKingBotEvaluationTest"
 * → server/build/skullking-bot-strength.txt
 * </pre>
 *
 * 시드: 평가 {@value #BASE} 계열 + 홀드아웃 {@value #HOLDOUT} 계열. 상수는 이 둘과 다른 555
 * 계열에서 정했다.
 */
@EnabledIfEnvironmentVariable(named = "MIRBOARD_BOT_EVAL", matches = "1")
class SkullKingBotEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(SkullKingBotEvaluationTest.class);

    private static final long BASE = 20261003L;
    private static final long HOLDOUT = 777L;
    private static final int MATCHES = 200;

    @Test
    void strength_table() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# D-230 스컬킹 봇 강도 — 초점 1좌석 대 나머지, 인원별 ").append(MATCHES)
                .append("판 (괄호: 홀드아웃)\n");
        out.append("# gap = 초점 평균 − 상대 평균, share = 초점 1위 몫(공동 1위 나눔), hit = 예측 적중률\n\n");
        for (Policy focal : new Policy[] {Policy.MODEL, Policy.HEURISTIC}) {
            for (Policy others : new Policy[] {Policy.HEURISTIC, Policy.WEAKEST, Policy.RANDOM}) {
                if (focal == others) {
                    continue;
                }
                out.append("## ").append(focal).append(" 1 대 ").append(others).append(" n−1\n");
                out.append("| n | 점수 차 | 승률 | 평균 점수 | 적중률 |\n|---|---|---|---|---|\n");
                for (int n = 2; n <= 8; n++) {
                    Stat s = BotMatchHarness.run(n, MATCHES, BASE, 1, focal, others);
                    Stat h = BotMatchHarness.run(n, MATCHES, HOLDOUT, 1, focal, others);
                    out.append(String.format("| %d | %+.0f ±%.0f (%+.0f) | %.3f (%.3f) | %.0f (%.0f) | %.3f (%.3f) |%n",
                            n, s.gap(), s.gapSe(), h.gap(), s.share(), h.share(),
                            s.mean(), h.mean(), s.hitRate(), h.hitRate()));
                }
                out.append('\n');
            }
        }

        out.append("## MODEL 자가대전 (인원별 ").append(MATCHES / 4).append("판)\n");
        out.append("| n | 평균 점수 | 적중률 |\n|---|---|---|\n");
        for (int n = 2; n <= 8; n++) {
            Stat s = BotMatchHarness.run(n, MATCHES / 4, BASE + 90_000, n, Policy.MODEL, Policy.MODEL);
            out.append(String.format("| %d | %.0f | %.3f |%n", n, s.mean(), s.hitRate()));
        }

        out.append("\n## 한 수 결정 시간 (MODEL 자가대전, 한 스레드, 인원별 8판)\n");
        out.append("| n | 결정 수 | p50 ms | p99 ms | 최대 ms |\n|---|---|---|---|---|\n");
        for (int n = 2; n <= 8; n++) {
            long[] t = decisionNanos(n, 8);
            out.append(String.format("| %d | %d | %.2f | %.2f | %.2f |%n", n, t.length,
                    t[t.length / 2] / 1e6, t[(int) (t.length * 0.99)] / 1e6, t[t.length - 1] / 1e6));
        }

        Path file = Path.of("build", "skullking-bot-strength.txt");
        Files.writeString(file, out);
        log.info("[D-230] strength table → {}\n{}", file.toAbsolutePath(), out);
    }

    /** MODEL 자가대전 결정 시간(ns, 오름차순) — 첫 판은 워밍업으로 버린다. */
    static long[] decisionNanos(int n, int matches) {
        Policy[] seats = new Policy[n];
        Arrays.fill(seats, Policy.MODEL);
        BotMatchHarness.play(n, 1L, seats, null);
        List<Long> nanos = new ArrayList<>();
        for (int m = 0; m < matches; m++) {
            BotMatchHarness.play(n, 4242L + m, seats, nanos::add);
        }
        return nanos.stream().mapToLong(Long::longValue).sorted().toArray();
    }
}
