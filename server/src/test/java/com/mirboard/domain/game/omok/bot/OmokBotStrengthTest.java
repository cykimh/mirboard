package com.mirboard.domain.game.omok.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokRule;
import org.junit.jupiter.api.Test;

/**
 * 봇 3단계가 서로 갈리는가 (D-179, 설계서 §4.4). 1:1 렌주, 듀플리케이트(여는 두 수 하나로 흑백을 바꿔 두 판), 시드 고정. 기본
 * 실행은 작은 판 수, {@code MIRBOARD_BOT_EVAL=1} 이면 200판으로 돈다.
 *
 * <p>어려움 대 보통은 설계서 목표 0.8 이 기준이다 — S2(D-179)의 VCF·VCT 만으로는 0.700 이었고, D-191 의 칸 점수 표 + 반복 심화
 * 알파베타 + 섞은 VCT 로 200판 1.000(렌주, 시드 4,000·9,000 모두)이 됐다.
 */
class OmokBotStrengthTest {

    private static final boolean FULL = "1".equals(System.getenv("MIRBOARD_BOT_EVAL"));

    private static int games(int small) {
        return FULL ? 200 : small;
    }

    @Test
    void normal_crushes_random() {
        double rate = BotMatches.winRate(OmokRule.RENJU, BotLevel.NORMAL, null, games(20), 1_000);
        System.out.printf("omok bot NORMAL vs RANDOM: %.3f%n", rate);
        assertThat(rate).isGreaterThanOrEqualTo(0.99);
    }

    @Test
    void easy_beats_random() {
        double rate = BotMatches.winRate(OmokRule.RENJU, BotLevel.EASY, null, games(20), 2_000);
        System.out.printf("omok bot EASY vs RANDOM: %.3f%n", rate);
        assertThat(rate).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void normal_beats_easy() {
        double rate = BotMatches.winRate(OmokRule.RENJU, BotLevel.NORMAL, BotLevel.EASY, games(40), 3_000);
        System.out.printf("omok bot NORMAL vs EASY: %.3f%n", rate);
        assertThat(rate).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void hard_beats_normal() {
        double rate = BotMatches.winRate(OmokRule.RENJU, BotLevel.HARD, BotLevel.NORMAL, games(40), 4_000);
        System.out.printf("omok bot HARD vs NORMAL: %.3f%n", rate);
        assertThat(rate).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void hard_beats_normal_under_every_rule() {
        // 금수 없는 룰·삼삼만에서도 — 흑의 금수 근사가 틀려도 강도가 무너지지 않는지.
        for (OmokRule rule : new OmokRule[] {OmokRule.FREESTYLE, OmokRule.DOUBLE_THREE_ONLY}) {
            double rate = BotMatches.winRate(rule, BotLevel.HARD, BotLevel.NORMAL, games(20), 5_000);
            System.out.printf("omok bot HARD vs NORMAL (%s): %.3f%n", rule, rate);
            assertThat(rate).as(rule.name()).isGreaterThanOrEqualTo(0.8);
        }
    }
}
