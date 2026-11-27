package com.mirboard.domain.game.wordtemp.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.wordtemp.scoring.HintJudge.Verdict;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>진짜 API 를 부른다 — CI·평소 테스트에서는 돌지 않는다.</b> {@code MIRBOARD_WORDTEMP_LIVE_SMOKE=1} 과 {@code ANTHROPIC_API_KEY} 가
 * 둘 다 있을 때만 켜진다(운영자 PC 에서 한 번씩 — 호출 6번, 약 $0.02):
 *
 * <pre>
 * MIRBOARD_WORDTEMP_LIVE_SMOKE=1 ANTHROPIC_API_KEY=… ./gradlew :server:test --rerun \
 *     --tests "com.mirboard.domain.game.wordtemp.scoring.ClaudeHintJudgeLiveSmokeTest"
 * </pre>
 *
 * 보는 것: SDK 요청 꼴(구조화 출력·effort low·서버 쪽 fallbacks)이 받아들여지는지, 답이 스키마대로 읽히는지, {@code score-v2} 가 청소한
 * 표와 같은 판정(고유명사·비하 표현·명사 아님 → 무효)을 하는지, 지연이 SDK 시간 제한 5초 안인지. 키·응답 원문은 찍지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "MIRBOARD_WORDTEMP_LIVE_SMOKE", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeHintJudgeLiveSmokeTest {

    private static final AnswerEntry SEA = new AnswerEntry("바다", "nature",
            "지구 표면의 대부분을 덮고 있는 짠물이 넓게 고인 곳", List.of());

    private final ClaudeHintJudge judge = new ClaudeHintJudge(System.getenv("ANTHROPIC_API_KEY"),
            System.getenv().getOrDefault("MIRBOARD_WORD_TEMP_MODEL", "claude-sonnet-5-5"), "score-v3",
            Duration.ofSeconds(5));

    private Verdict ask(String hint) {
        long started = System.nanoTime();
        Verdict verdict = judge.judge(SEA, hint);
        System.out.printf("[wordtemp-smoke] 바다/%s → valid=%s score=%d (%dms)%n", hint, verdict.validNoun(),
                verdict.score(), (System.nanoTime() - started) / 1_000_000);
        return verdict;
    }

    @Test
    void common_nouns_get_a_score_in_the_expected_band() {
        Verdict close = ask("짠맛");
        assertThat(close.validNoun()).isTrue();
        assertThat(close.score()).isBetween(40, 95);
        Verdict far = ask("연필");
        assertThat(far.validNoun()).isTrue();
        assertThat(far.score()).isBetween(0, 39);
        Verdict hot = ask("파도");
        assertThat(hot.validNoun()).isTrue();
        assertThat(hot.score()).isBetween(75, 95);
    }

    @Test
    void proper_nouns_slurs_and_non_nouns_are_invalid_under_score_v2() {
        assertThat(ask("베토벤").validNoun()).as("사람 이름").isFalse();
        assertThat(ask("넷플릭스").validNoun()).as("상표").isFalse();
        assertThat(ask("달리는").validNoun()).as("활용형").isFalse();
    }
}
