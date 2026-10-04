package com.mirboard.domain.game.onecard.card;

import java.util.ArrayList;
import java.util.List;

/** 54장 덱 — 무늬 52 + 조커 2 (`docs/rules-onecard.md` §1). 섞기는 {@code Dealer} 가 한다. */
public final class Deck {

    public static final int SIZE = 54;

    private static final List<PlayingCard> ALL = build();

    private Deck() {
    }

    /** 54장 전부 — 무늬 순(♠♥♦♣), 무늬 안은 A→K, 마지막에 흑백·컬러 조커. */
    public static List<PlayingCard> all() {
        return ALL;
    }

    private static List<PlayingCard> build() {
        List<PlayingCard> cards = new ArrayList<>(SIZE);
        for (Suit suit : Suit.values()) {
            for (int rank = PlayingCard.ACE; rank <= PlayingCard.KING; rank++) {
                cards.add(PlayingCard.of(suit, rank));
            }
        }
        cards.add(PlayingCard.joker(Joker.BLACK));
        cards.add(PlayingCard.joker(Joker.COLOR));
        return List.copyOf(cards);
    }
}
