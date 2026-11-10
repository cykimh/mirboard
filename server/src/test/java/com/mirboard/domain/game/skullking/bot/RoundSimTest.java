package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.skullking.SkullKingEngine;
import com.mirboard.domain.game.skullking.action.SkullKingAction;
import com.mirboard.domain.game.skullking.action.SkullKingAction.PlayCard;
import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-230 — 롤아웃 시뮬레이터가 엔진과 같은 라운드를 굴리는가. 엔진이 분배한 라운드를 시뮬레이터의
 * 굴리기 정책으로 끝까지 두되, 매 수를 <b>엔진 합법수에 있는지</b> 확인하고 엔진에도 적용한다.
 * 끝나면 좌석별 승수·라운드 점수(보너스 포함)가 엔진 정산과 같아야 한다 — 차례 순서·트릭 승자·
 * 다음 리드·보너스 누적·점수식이 모두 엔진과 맞는다는 뜻이다. 인원 2~8 × 라운드 1~10 × 시드 6.
 */
class RoundSimTest {

    private static final SkullCard[] CARD_OF = new SkullCard[CardCode.CODES];

    static {
        for (SkullCard card : Deck.unshuffled().cards()) {
            CARD_OF[CardCode.of(card)] = card;
        }
    }

    @Test
    void rollouts_replay_identically_in_the_engine() {
        int rounds = 0;
        for (int n = 2; n <= 8; n++) {
            List<Long> ids = new ArrayList<>();
            for (long i = 0; i < n; i++) {
                ids.add(i + 1);
            }
            SkullKingEngine engine = new SkullKingEngine(new GameContext("sim", ids));
            for (int round = 1; round <= SkullKingMatchState.TOTAL_ROUNDS; round++) {
                for (long seed = 0; seed < 6; seed++) {
                    replay(engine, n, round, seed);
                    rounds++;
                }
            }
        }
        assertThat(rounds).isEqualTo(7 * 10 * 6);
    }

    private static void replay(SkullKingEngine engine, int n, int round, long seed) {
        Random random = new Random(seed * 131 + round * 7L + n);
        SkullKingMatchState match = new SkullKingMatchState(
                round, (int) (seed % n), Map.of());
        SkullKingState state = engine.startRound(match, random).newState();
        int handSize = state.players().get(0).handSize();

        RoundSim sim = new RoundSim(n, round, handSize);
        for (int s = 0; s < n; s++) {
            List<SkullCard> hand = state.players().get(s).hand();
            for (int i = 0; i < hand.size(); i++) {
                sim.hand[s][i] = CardCode.of(hand.get(i));
            }
            sim.handLen[s] = hand.size();
            sim.bid[s] = random.nextInt(handSize + 1);
            state = engine.apply(state, s, new SkullKingAction.PlaceBid(sim.bid[s])).newState();
        }
        SkullKingState.Playing playing = (SkullKingState.Playing) state;
        sim.leader = playing.trick().leadSeat();
        sim.remaining = n * handSize;

        while (sim.remaining > 0) {
            int seat = sim.toAct();
            assertThat(engine.pendingSeats(state)).containsExactly(seat);
            int move = sim.greedy(seat);
            int index = move >>> 8;
            int played = move & 0xFF;
            SkullCard card = CARD_OF[sim.hand[seat][index]];
            PlayCard action = card.equals(SkullCard.tigress())
                    ? PlayCard.tigress(played == CardCode.PIRATE ? TigressMode.PIRATE : TigressMode.ESCAPE)
                    : PlayCard.of(card);
            assertThat(engine.legalActions(state, seat)).as("n=%d r=%d 굴리기 수", n, round)
                    .contains(action);
            state = engine.apply(state, seat, action).newState();
            sim.play(seat, index, played);
        }

        SkullKingState.RoundEnd ended = (SkullKingState.RoundEnd) state;
        for (int s = 0; s < n; s++) {
            PlayerState player = ended.players().get(s);
            RoundScore score = ended.scores().get(s);
            assertThat(sim.won[s]).as("n=%d r=%d 좌석 %d 승수", n, round, s)
                    .isEqualTo(player.tricksWonCount());
            assertThat(sim.score(s)).as("n=%d r=%d 좌석 %d 점수", n, round, s)
                    .isEqualTo(score.total());
        }
    }
}
