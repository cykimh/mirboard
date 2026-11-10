package com.mirboard.domain.game.skullking.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.action.ActionValidator;
import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.scoring.BonusCalculator;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.state.TrickState;
import com.mirboard.domain.game.skullking.trick.LeadSuitResolver;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-230 — 정수 규칙 거울({@link CardCode})이 정본(`TrickResolver`·`LeadSuitResolver`·
 * `BonusCalculator`·`ActionValidator.followsLeadSuit`)과 같은가. 무작위 트릭 20만 개(1~8장,
 * 티그리스 선언 무작위)를 두 쪽에 넣어 승자·리드 수트·보너스·follow 판정이 하나도 어긋나지
 * 않아야 한다. 룰이 바뀌어 정본만 고치면 여기가 먼저 빨개진다.
 */
class CardCodeTest {

    private static final int TRICKS = 200_000;

    @Test
    void codes_are_a_bijection_on_card_kinds() {
        Set<Integer> codes = new HashSet<>();
        Set<SkullCard> kinds = new HashSet<>(Deck.unshuffled().cards());
        for (SkullCard card : kinds) {
            int code = CardCode.of(card);
            assertThat(code).isBetween(0, CardCode.CODES - 1);
            codes.add(code);
            if (card.isSuit()) {
                assertThat(CardCode.suit(code)).isEqualTo(card.suit().ordinal());
                assertThat(CardCode.rank(code)).isEqualTo(card.rank());
            }
        }
        assertThat(codes).hasSize(kinds.size()).hasSize(CardCode.CODES);
    }

    @Test
    void mirror_matches_the_rule_engine_on_random_tricks() {
        Random random = new Random(20261110L);
        List<SkullCard> deck = new ArrayList<>(Deck.unshuffled().cards());
        int[] codes = new int[8];
        for (int t = 0; t < TRICKS; t++) {
            Collections.shuffle(deck, random);
            int len = 1 + random.nextInt(8);
            List<PlayedCard> played = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                SkullCard card = deck.get(i);
                PlayedCard pc = card.is(SpecialKind.TIGRESS)
                        ? PlayedCard.tigress(i, random.nextBoolean()
                                ? TigressMode.PIRATE : TigressMode.ESCAPE)
                        : PlayedCard.of(i, card);
                played.add(pc);
                codes[i] = CardCode.played(pc);
            }

            int lead = LeadSuitResolver.resolve(played).map(Enum::ordinal).orElse(-1);
            assertThat(CardCode.leadSuit(codes, len)).as("리드 수트 %s", played).isEqualTo(lead);

            TrickResult result = TrickResolver.resolve(played);
            int w = CardCode.winner(codes, len);
            assertThat(w).as("승자 %s", played).isEqualTo(result.winnerSeat());
            assertThat(CardCode.bonus(codes, len, w)).as("보너스 %s", played)
                    .isEqualTo(BonusCalculator.bonusFor(List.of(result)));

            // follow 의무 — 다음 사람이 덱의 다음 3장을 손에 들었다고 보고 카드마다 비교.
            List<SkullCard> hand = deck.subList(len, len + 3);
            int[] handCodes = hand.stream().mapToInt(CardCode::of).toArray();
            boolean follow = CardCode.mustFollow(handCodes, handCodes.length, lead);
            TrickState trick = new TrickState(0, played);
            for (SkullCard card : hand) {
                assertThat(CardCode.playable(CardCode.of(card), follow, lead))
                        .as("follow %s ← %s (손 %s)", played, card, hand)
                        .isEqualTo(ActionValidator.followsLeadSuit(trick, hand, card));
            }
        }
    }
}
