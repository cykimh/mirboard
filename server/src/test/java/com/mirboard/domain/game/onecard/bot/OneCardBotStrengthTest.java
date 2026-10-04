package com.mirboard.domain.game.onecard.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-128 — 봇 강도 평가 (Docker 불필요). 고정 시드 판을 돌려 "휴리스틱이 포트 기본 봇(합법수 균등 무작위)을
 * 확실히 이기는가"를 숫자로 강제한다(스컬킹 D-119 방식).
 *
 * <p>잴 것은 수 선택이라 외치기 경쟁은 늘 주인이 먼저 외친다(벌칙 없음). 초점 좌석은 판마다 돌려 좌석 이점을
 * 지운다. 하한은 실측 − 3·SE 아래로 잡았고, 대조군(초점도 무작위)이 같은 하한을 넘지 못한다는 것으로 하한이
 * 의미 있음을 보인다. 측정값은 INFO 로그로 남는다.
 */
class OneCardBotStrengthTest {

    private static final Logger log = LoggerFactory.getLogger(OneCardBotStrengthTest.class);

    private static final long BASE = 20261004L;
    private static final int GAMES = 1_000;
    /** 차례 상한(600) + 경쟁 창. 넘으면 끝나지 않는 판이다. */
    private static final int STEP_GUARD = 3 * OneCardEngine.TURN_LIMIT;

    /** 휴리스틱 1명 대 무작위 3명 — 기준 0.25. 실측 0.919 (SE 0.009), 대조군 0.245. */
    private static final double MIN_SHARE_FOUR = 0.85;
    /** 휴리스틱 대 무작위 1:1 — 기준 0.5. 실측 0.960 (SE 0.006), 대조군 0.485. */
    private static final double MIN_SHARE_TWO = 0.9;
    /** 휴리스틱 1명 대 탐욕 3명 — 기준 0.25. 실측 0.356 (SE 0.015), 대조군 0.268. */
    private static final double MIN_SHARE_GREEDY = 0.31;

    private enum Policy {
        /** {@link OneCardBotPolicy}. */
        HEURISTIC,
        /** 포트 기본 봇 — 먹기를 포함한 합법수 균등 무작위. */
        RANDOM,
        /** 낼 수 있으면 아무 카드나 무작위로 내고, 못 낼 때만 먹는다. */
        GREEDY
    }

    private static List<Long> ids(int seats) {
        return LongStream.range(0, seats).map(i -> 100 + i).boxed().toList();
    }

    private static OneCardAction pick(Policy policy, OneCardState state, int seat, List<OneCardAction> legal,
                                      Random random) {
        return switch (policy) {
            case HEURISTIC -> OneCardBotPolicy.choose(OneCardBotView.of(state, seat), legal);
            case RANDOM -> legal.get(random.nextInt(legal.size()));
            case GREEDY -> {
                List<OneCardAction> plays = legal.stream()
                        .filter(OneCardAction.PlayCard.class::isInstance).toList();
                yield plays.isEmpty() ? new OneCardAction.Draw() : plays.get(random.nextInt(plays.size()));
            }
        };
    }

    /** 초점 좌석이 1등(동순위 포함)인 판의 비율. */
    private static double focalWinShare(int seats, Policy focalPolicy, Policy otherPolicy) {
        int wins = 0;
        for (int game = 0; game < GAMES; game++) {
            int focal = game % seats;
            Random others = new Random(BASE * 31 + game);
            OneCardEngine engine = new OneCardEngine(new GameContext("eval", ids(seats)), new Random(BASE + game));
            OneCardState state = engine.startMatch().newState();
            for (int step = 0; step < STEP_GUARD && !state.ended(); step++) {
                RaceWindow race = state.race();
                if (race != null) {
                    state = engine.apply(state, race.ownerSeat(),
                            new OneCardAction.CallOneCard(race.raceId()), 0).newState();
                    continue;
                }
                int seat = state.turnSeat();
                List<OneCardAction> legal = engine.legalActions(state, seat);
                OneCardAction action = pick(seat == focal ? focalPolicy : otherPolicy, state, seat, legal, others);
                state = engine.apply(state, seat, action, 0).newState();
            }
            assertThat(state.ended()).as("판 %d 이 끝나지 않았다", game).isTrue();
            if (state.result().winners().contains(focal)) {
                wins++;
            }
        }
        double share = (double) wins / GAMES;
        log.info("[D-128] seats={} {}:{} share={} (SE {})", seats, focalPolicy, otherPolicy,
                String.format("%.3f", share), String.format("%.3f", Math.sqrt(share * (1 - share) / GAMES)));
        return share;
    }

    @Test
    void the_heuristic_beats_three_random_bots() {
        assertThat(focalWinShare(4, Policy.HEURISTIC, Policy.RANDOM)).isGreaterThanOrEqualTo(MIN_SHARE_FOUR);
        assertThat(focalWinShare(4, Policy.RANDOM, Policy.RANDOM)).as("대조군").isLessThan(MIN_SHARE_FOUR);
    }

    @Test
    void the_heuristic_beats_a_random_bot_head_to_head() {
        assertThat(focalWinShare(2, Policy.HEURISTIC, Policy.RANDOM)).isGreaterThanOrEqualTo(MIN_SHARE_TWO);
        assertThat(focalWinShare(2, Policy.RANDOM, Policy.RANDOM)).as("대조군").isLessThan(MIN_SHARE_TWO);
    }

    /** "낼 수 있으면 낸다"만으로 얻는 몫을 넘어 규칙(약한 반격·압박·무늬 유지·공격 카드 아끼기)이 값을 하는가. */
    @Test
    void the_heuristic_beats_greedy_bots_that_always_play_when_they_can() {
        assertThat(focalWinShare(4, Policy.HEURISTIC, Policy.GREEDY)).isGreaterThanOrEqualTo(MIN_SHARE_GREEDY);
        assertThat(focalWinShare(4, Policy.GREEDY, Policy.GREEDY)).as("대조군").isLessThan(MIN_SHARE_GREEDY);
    }
}
