package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 분배와 시작 카드 (`docs/rules-onecard.md` §3).
 *
 * <p>섞기는 {@link Shuffler} 로 주입한다 — 운영은 난수 셔플이고, 테스트는 정해 둔 순서를 준다. §3-5 를
 * 확인하려면 첫 분배는 일반 카드를 모두 손패로 보내고 다시 나눌 때 다른 순서를 주는 셔플러가 필요하다(§14).
 */
public final class Dealer {

    public static final int HAND_SIZE = 7;
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 6;

    /** 54장을 섞은 새 목록을 돌려준다. 입력은 바꾸지 않는다. */
    @FunctionalInterface
    public interface Shuffler {
        List<PlayingCard> shuffle(List<PlayingCard> cards);
    }

    /**
     * 분배 결과.
     *
     * @param drawPile 0번이 맨 위
     */
    public record Deal(List<List<PlayingCard>> hands, List<PlayingCard> drawPile, PlayingCard startCard) {
        public Deal {
            hands = hands.stream().<List<PlayingCard>>map(List::copyOf).toList();
            drawPile = List.copyOf(drawPile);
        }
    }

    private Dealer() {
    }

    public static Shuffler random(Random rng) {
        return cards -> {
            List<PlayingCard> copy = new ArrayList<>(cards);
            Collections.shuffle(copy, rng);
            return copy;
        };
    }

    /**
     * §3 — 섞어서 7장씩 나누고 시작 카드를 뒤집는다. 일반 카드가 아니면 뽑을 더미 맨 아래로 보내고 다음을
     * 뒤집으며(§3-4), 더미를 한 바퀴 돌아도 없으면 54장을 다시 섞어 처음부터 한다(§3-5).
     */
    public static Deal deal(int seatCount, Shuffler shuffler) {
        if (seatCount < MIN_SEATS || seatCount > MAX_SEATS) {
            throw new IllegalArgumentException("One Card needs 2-6 seats: " + seatCount);
        }
        while (true) {
            List<PlayingCard> deck = shuffler.shuffle(Deck.all());
            List<List<PlayingCard>> hands = new ArrayList<>();
            for (int seat = 0; seat < seatCount; seat++) {
                hands.add(deck.subList(seat * HAND_SIZE, (seat + 1) * HAND_SIZE));
            }
            ArrayDeque<PlayingCard> pile = new ArrayDeque<>(deck.subList(seatCount * HAND_SIZE, deck.size()));
            for (int flips = pile.size(); flips > 0; flips--) {
                PlayingCard top = pile.pollFirst();
                if (top.isNormal()) {
                    return new Deal(hands, List.copyOf(pile), top);
                }
                pile.addLast(top);
            }
        }
    }
}
