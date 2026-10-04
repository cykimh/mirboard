package com.mirboard.domain.game.onecard;

import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.Joker;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 테스트용 상태 빌더. 손패·맨 위·뽑을 더미만 정하면 나머지 카드는 버린 더미의 맨 위 아래에 넣어 54장 보존을
 * 지킨다 — 그래서 만든 상태가 그대로 불변식 검사를 통과한다.
 */
final class OneCardTables {

    static final PlayingCard BLACK_JOKER = PlayingCard.joker(Joker.BLACK);
    static final PlayingCard COLOR_JOKER = PlayingCard.joker(Joker.COLOR);

    private final List<List<PlayingCard>> hands;
    private PlayingCard top = heart(5);
    private List<PlayingCard> drawPile;
    private int turnSeat;
    private int direction = 1;
    private Suit declaredSuit;
    private int attackStack;
    private RaceWindow race;
    private final List<Elimination> eliminations = new ArrayList<>();
    private int passStreak;
    private int turnCount;

    private OneCardTables(List<List<PlayingCard>> hands) {
        this.hands = hands;
    }

    @SafeVarargs
    static OneCardTables seats(List<PlayingCard>... hands) {
        List<List<PlayingCard>> list = new ArrayList<>();
        for (List<PlayingCard> hand : hands) {
            list.add(hand);
        }
        return new OneCardTables(list);
    }

    static List<PlayingCard> hand(PlayingCard... cards) {
        return List.of(cards);
    }

    static PlayingCard spade(int rank) {
        return PlayingCard.of(Suit.SPADE, rank);
    }

    static PlayingCard heart(int rank) {
        return PlayingCard.of(Suit.HEART, rank);
    }

    static PlayingCard diamond(int rank) {
        return PlayingCard.of(Suit.DIAMOND, rank);
    }

    static PlayingCard club(int rank) {
        return PlayingCard.of(Suit.CLUB, rank);
    }

    OneCardTables top(PlayingCard card) {
        this.top = card;
        return this;
    }

    /** 뽑을 더미를 정확히 이 카드들로(0번이 맨 위). 정하지 않으면 남은 카드 전부. */
    OneCardTables drawPile(PlayingCard... cards) {
        this.drawPile = List.of(cards);
        return this;
    }

    OneCardTables turn(int seat) {
        this.turnSeat = seat;
        return this;
    }

    OneCardTables direction(int direction) {
        this.direction = direction;
        return this;
    }

    OneCardTables declared(Suit suit) {
        this.declaredSuit = suit;
        return this;
    }

    OneCardTables attack(int stack) {
        this.attackStack = stack;
        return this;
    }

    OneCardTables race(RaceWindow race) {
        this.race = race;
        this.turnSeat = -1;
        return this;
    }

    /** 그 좌석의 손패는 빈 목록이어야 한다(탈락자 손패는 더미로 갔다). */
    OneCardTables eliminated(int seat, Elimination.Reason reason, int cardsHeld) {
        this.eliminations.add(new Elimination(seat, reason, cardsHeld));
        return this;
    }

    OneCardTables passStreak(int streak) {
        this.passStreak = streak;
        return this;
    }

    OneCardTables turnCount(int count) {
        this.turnCount = count;
        return this;
    }

    OneCardState build() {
        List<PlayingCard> used = new ArrayList<>();
        hands.forEach(used::addAll);
        used.add(top);
        if (drawPile != null) {
            used.addAll(drawPile);
        }
        Set<PlayingCard> seen = new HashSet<>();
        for (PlayingCard card : used) {
            if (!seen.add(card)) {
                // 같은 카드가 두 군데 있는 상태는 54장 보존이 깨진 채 시작하므로 테스트 작성 실수다.
                throw new IllegalArgumentException("card used twice in the hands, the top or the draw pile: " + card);
            }
        }
        List<PlayingCard> rest = Deck.all().stream().filter(card -> !used.contains(card)).toList();
        List<PlayingCard> pile = drawPile != null ? drawPile : rest;
        List<PlayingCard> discard = new ArrayList<>(drawPile != null ? rest : List.of());
        discard.add(top);
        return new OneCardState(hands, pile, discard, turnSeat, direction, declaredSuit, attackStack, race,
                eliminations, passStreak, turnCount, 1, null);
    }
}
