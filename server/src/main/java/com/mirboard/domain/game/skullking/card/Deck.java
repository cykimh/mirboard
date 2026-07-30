package com.mirboard.domain.game.skullking.card;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 70장의 스컬킹 덱 — 색상 56 (4색 × 1~14) + 특수 14 (`docs/rules-skullking.md` §1).
 *
 * <p>상급자 카드 4장(약탈품 2·크라켄 1·흰고래 1)은 범위 밖이라 넣지 않는다 (§14).
 * 넣기로 바꾸면 {@link #SIZE} 가 바뀌고 §4 의 8인 분배 예외표를 다시 계산해야 한다 —
 * {@code Dealer.handSize} 가 본 상수를 읽으므로 상수만 고치면 식은 따라온다.
 *
 * <p>셔플은 결정적 테스트를 위해 {@link Random} 오버로드를 제공하지만, 운영 코드에서는
 * 항상 {@link SecureRandom} 을 쓴다 (티츄 {@code Deck} 과 같은 규약).
 */
public final class Deck {

    /** 덱 전체 장수. 라운드에서 실제로 쓰는 장수는 이보다 적을 수 있다 (§4 8인 예외). */
    public static final int SIZE = 70;

    /** 색상 카드 장수 — 4색 × 14. */
    public static final int SUIT_CARD_COUNT = 56;

    private static final List<SkullCard> ALL_CARDS = buildAllCards();

    private final List<SkullCard> cards;

    private Deck(List<SkullCard> cards) {
        this.cards = List.copyOf(cards);
    }

    public static Deck unshuffled() {
        return new Deck(ALL_CARDS);
    }

    public static Deck shuffled(SecureRandom rng) {
        return shuffleWith(rng);
    }

    /** 시드 고정이 필요한 테스트 전용. 운영 코드에서는 사용 금지. */
    public static Deck shuffled(Random rng) {
        return shuffleWith(rng);
    }

    private static Deck shuffleWith(Random rng) {
        List<SkullCard> copy = new ArrayList<>(ALL_CARDS);
        Collections.shuffle(copy, rng);
        return new Deck(copy);
    }

    public List<SkullCard> cards() {
        return cards;
    }

    public int size() {
        return cards.size();
    }

    private static List<SkullCard> buildAllCards() {
        List<SkullCard> all = new ArrayList<>(SIZE);
        for (SkullSuit suit : SkullSuit.values()) {
            for (int rank = SkullSuit.MIN_RANK; rank <= SkullSuit.MAX_RANK; rank++) {
                all.add(SkullCard.of(suit, rank));
            }
        }
        for (SpecialKind kind : SpecialKind.values()) {
            for (int i = 0; i < kind.countInDeck(); i++) {
                all.add(SkullCard.special(kind));
            }
        }
        return List.copyOf(all);
    }
}
