package com.mirboard.domain.game.tichu.card;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 56장의 티츄 덱. 본 클래스는 카드 컬렉션을 불변으로 캡슐화한다. 셔플은 결정적
 * 테스트를 위해 {@link Random} 오버로드를 제공하지만, 운영 코드에서는 항상
 * {@link SecureRandom} 인스턴스를 사용해야 한다.
 */
public final class Deck {

    public static final int SIZE = 56;

    private static final List<Card> ALL_CARDS = buildAllCards();

    private final List<Card> cards;

    private Deck(List<Card> cards) {
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
        List<Card> copy = new ArrayList<>(ALL_CARDS);
        Collections.shuffle(copy, rng);
        return new Deck(copy);
    }

    public List<Card> cards() {
        return cards;
    }

    public int size() {
        return cards.size();
    }

    private static List<Card> buildAllCards() {
        List<Card> all = new ArrayList<>(SIZE);
        for (Suit suit : Suit.values()) {
            for (int rank = 2; rank <= 14; rank++) {
                all.add(Card.normal(suit, rank));
            }
        }
        all.add(Card.mahjong());
        all.add(Card.dog());
        all.add(Card.phoenix());
        all.add(Card.dragon());
        return List.copyOf(all);
    }
}
