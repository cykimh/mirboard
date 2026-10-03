package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-119 — 승률 추정. 판정은 {@link TrickResolver} 에 맡기고(룰 복제 0), 모델이 하는 일은
 * "판을 뒤집는 미공개 카드 수"를 세어 비복원 곱으로 묶는 것뿐이다. 정확한 범위(마지막 순번,
 * 미공개 1장)와 근사 한계(미공개 2장 이상의 상호작용)를 둘 다 단언으로 고정한다.
 */
class TrickOddsTest {

    private static final int ME = 0;

    private static SkullCard green(int rank) {
        return SkullCard.of(SkullSuit.GREEN, rank);
    }

    private static SkullCard yellow(int rank) {
        return SkullCard.of(SkullSuit.YELLOW, rank);
    }

    private static SkullCard black(int rank) {
        return SkullCard.of(SkullSuit.BLACK, rank);
    }

    /** 덱 70장에서 주어진 카드들을 multiset 으로 뺀 미공개 풀. */
    private static List<SkullCard> deckWithout(SkullCard... removed) {
        List<SkullCard> pool = new ArrayList<>(Deck.unshuffled().cards());
        for (SkullCard card : removed) {
            assertThat(pool.remove(card)).as("덱에 %s 가 남아 있어야 한다", card).isTrue();
        }
        return pool;
    }

    /** 4인 · 공개 0장에서 이 카드로 리드했을 때의 승률. */
    private static double leadOdds(SkullCard card) {
        return TrickOdds.winProbability(List.of(), PlayedCard.of(ME, card), 3, deckWithout(card));
    }

    @Test
    void last_to_play_is_exact() {
        List<PlayedCard> played = List.of(PlayedCard.of(1, green(10)));
        // 미공개 카드가 잔뜩 남아 있어도 뒤에 낼 사람이 없으면 승패는 지금 판정 그대로다.
        List<SkullCard> unseen = deckWithout(green(10), green(12), green(9), yellow(14), black(1));

        assertThat(TrickOdds.winProbability(played, PlayedCard.of(ME, green(12)), 0, unseen))
                .isEqualTo(1.0);
        assertThat(TrickOdds.winProbability(played, PlayedCard.of(ME, green(9)), 0, unseen))
                .isEqualTo(0.0);
        assertThat(TrickOdds.winProbability(played, PlayedCard.of(ME, yellow(14)), 0, unseen))
                .as("오프수트는 숫자가 커도 진다")
                .isEqualTo(0.0);
        assertThat(TrickOdds.winProbability(played, PlayedCard.of(ME, black(1)), 0, unseen))
                .as("검정은 오프수트여도 이긴다 (§7.1)")
                .isEqualTo(1.0);

        for (SkullCard card : List.of(green(12), green(9), yellow(14), black(1))) {
            PlayedCard mine = PlayedCard.of(ME, card);
            List<PlayedCard> trick = new ArrayList<>(played);
            trick.add(mine);
            double expected = TrickResolver.resolve(trick).winnerSeat() == ME ? 1.0 : 0.0;
            assertThat(TrickOdds.winProbability(played, mine, 0, unseen))
                    .as("%s 는 TrickResolver 와 일치", card)
                    .isEqualTo(expected);
        }

        assertThat(TrickOdds.winProbability(played, PlayedCard.of(ME, green(12)), 2, List.of()))
                .as("미공개 풀이 비어 있어도 정확한 0/1")
                .isEqualTo(1.0);
    }

    @Test
    void skull_king_lead_is_certain_once_both_mermaids_are_seen() {
        PlayedCard skullKing = PlayedCard.of(ME, SkullCard.skullKing());

        double certain = TrickOdds.winProbability(List.of(), skullKing, 3, deckWithout(
                SkullCard.skullKing(), SkullCard.mermaid(), SkullCard.mermaid()));
        assertThat(certain).isEqualTo(1.0);

        double open = TrickOdds.winProbability(List.of(), skullKing, 3,
                deckWithout(SkullCard.skullKing()));
        // 미공개 69장 중 인어 2장 — (67·66·65)/(69·68·67).
        assertThat(open).isLessThan(1.0).isCloseTo(0.914, within(0.001));
    }

    @Test
    void losing_mermaid_can_be_rescued_by_unseen_skull_king() {
        List<PlayedCard> played = List.of(PlayedCard.of(1, SkullCard.pirate()));
        PlayedCard mermaid = PlayedCard.of(ME, SkullCard.mermaid());

        // 해적+인어 에 스컬킹이 더해지면 3자 예외로 인어가 이긴다 (§7 사다리 1단).
        double rescued = TrickOdds.winProbability(played, mermaid, 2,
                deckWithout(SkullCard.pirate(), SkullCard.mermaid()));
        assertThat(rescued).isGreaterThan(0.0).isCloseTo(0.029, within(0.001));

        double hopeless = TrickOdds.winProbability(played, mermaid, 2,
                deckWithout(SkullCard.pirate(), SkullCard.mermaid(), SkullCard.skullKing()));
        assertThat(hopeless).isEqualTo(0.0);
    }

    @Test
    void unseen_tigress_is_counted_as_a_pirate() {
        PlayedCard mermaidLead = PlayedCard.of(ME, SkullCard.mermaid());

        assertThat(TrickOdds.winProbability(List.of(), mermaidLead, 1, List.of(SkullCard.tigress())))
                .as("미공개 티그리스는 위협이 최대인 해적 선언으로 본다")
                .isEqualTo(0.0);
    }

    @Test
    void two_unseen_card_interaction_is_an_approximation() {
        PlayedCard mermaidLead = PlayedCard.of(ME, SkullCard.mermaid());

        assertThat(TrickOdds.winProbability(List.of(), mermaidLead, 1,
                List.of(SkullCard.skullKing())))
                .as("인어는 스컬킹을 이긴다 — 스컬킹은 뒤집는 카드가 아니다")
                .isEqualTo(1.0);

        double modeled = TrickOdds.winProbability(List.of(), mermaidLead, 2,
                List.of(SkullCard.pirate(), SkullCard.skullKing()));
        int actualWinner = TrickResolver.resolve(List.of(mermaidLead,
                PlayedCard.of(1, SkullCard.pirate()), PlayedCard.of(2, SkullCard.skullKing())))
                .winnerSeat();

        // 근사 한계 고정: 실제로는 해적+스컬킹이 함께 나와 3자 예외로 인어가 이기지만,
        // 모델은 미공개 카드를 한 장씩만 더해 보므로 "해적이 뒤집는다"로 0 을 낸다.
        // 모델을 바꾸면 이 단언이 먼저 알린다.
        assertThat(modeled).isEqualTo(0.0);
        assertThat(actualWinner).isEqualTo(ME);
    }

    @Test
    void lead_odds_are_monotone_and_deterministic() {
        double black14 = leadOdds(black(14));
        double black2 = leadOdds(black(2));
        double green14 = leadOdds(green(14));
        double green2 = leadOdds(green(2));
        double skullKing = leadOdds(SkullCard.skullKing());
        double mermaid = leadOdds(SkullCard.mermaid());

        assertThat(black14).isGreaterThan(black2);
        assertThat(green14).isGreaterThan(green2);
        assertThat(black14).as("으뜸패 14 > 비검정 14").isGreaterThan(green14);
        assertThat(skullKing).isGreaterThan(mermaid);

        assertThat(Double.doubleToRawLongBits(leadOdds(black(14))))
                .as("사칙연산만 쓰므로 비트 단위로 재현된다 (JEP 306)")
                .isEqualTo(Double.doubleToRawLongBits(black14));
        assertThat(Double.doubleToRawLongBits(leadOdds(SkullCard.skullKing())))
                .isEqualTo(Double.doubleToRawLongBits(skullKing));
    }
}
