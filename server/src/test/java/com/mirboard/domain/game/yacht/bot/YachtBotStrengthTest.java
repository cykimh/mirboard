package com.mirboard.domain.game.yacht.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.yacht.YachtEngine;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.score.Scoring;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 설계 §6.5 강도 확인 — 휴리스틱 봇이 무작위 봇(포트 기본 — 합법수 균등)과 탐욕 봇(한 번 굴리고 바로 최고점 칸)보다 낫다.
 * 시드 고정 1,000판(1인 점수 평균) + 탐욕 봇과 1:1 500판.
 */
class YachtBotStrengthTest {

    private static final int GAMES = 1_000;

    private static final BiFunction<YachtState, YachtEngine, YachtAction> HEURISTIC =
            (s, e) -> YachtBotPolicy.choose(YachtBotView.of(s, s.turnSeat()));

    private static final BiFunction<YachtState, YachtEngine, YachtAction> GREEDY = (s, e) -> s.turn().rolls() == 0
            ? YachtAction.Roll.plain()
            : new YachtAction.Score(Scoring.best(s.card(s.turnSeat()), s.turn().dice()));

    private static BiFunction<YachtState, YachtEngine, YachtAction> random(Random r) {
        return (s, e) -> {
            List<YachtAction> legal = e.legalActions(s, s.turnSeat());
            return legal.get(r.nextInt(legal.size()));
        };
    }

    private static double soloAverage(BiFunction<YachtState, YachtEngine, YachtAction> bot, long seed) {
        YachtEngine engine = new YachtEngine(new GameContext("s", List.of(1L), 0, 0, List.of(0), 0));
        DiceRoller roller = DiceRoller.from(new Random(seed));
        long sum = 0;
        for (int g = 0; g < GAMES; g++) {
            YachtState s = engine.start(0).newState();
            while (!s.ended()) {
                s = engine.apply(s, 0, bot.apply(s, engine), roller, 0).newState();
            }
            sum += s.card(0).total();
        }
        return (double) sum / GAMES;
    }

    @Test
    void the_heuristic_scores_more_than_greedy_and_random() {
        double heuristic = soloAverage(HEURISTIC, 11);
        double greedy = soloAverage(GREEDY, 11);
        double random = soloAverage(random(new Random(99)), 11);
        System.out.printf("yacht bot strength (solo avg of %d): heuristic %.1f · greedy %.1f · random %.1f%n",
                GAMES, heuristic, greedy, random);
        assertThat(heuristic).isGreaterThan(greedy + 20);
        assertThat(greedy).isGreaterThan(random);
    }

    @Test
    void the_heuristic_beats_greedy_head_to_head() {
        YachtEngine engine = new YachtEngine(new GameContext("h", List.of(1L, 2L), 0, 0, List.of(0, 1), 0));
        DiceRoller roller = DiceRoller.from(new Random(7));
        int wins = 0;
        int games = 500;
        for (int g = 0; g < games; g++) {
            // 선을 번갈아 둔다
            int heuristicSeat = g % 2;
            YachtState s = engine.start(0).newState();
            while (!s.ended()) {
                YachtAction a = s.turnSeat() == heuristicSeat ? HEURISTIC.apply(s, engine) : GREEDY.apply(s, engine);
                s = engine.apply(s, s.turnSeat(), a, roller, 0).newState();
            }
            if (s.card(heuristicSeat).total() > s.card(1 - heuristicSeat).total()) {
                wins++;
            }
        }
        double rate = (double) wins / games;
        System.out.printf("yacht bot heuristic vs greedy 1:1 win rate %.2f%n", rate);
        assertThat(rate).isGreaterThan(0.6);
    }
}
