package com.mirboard.domain.game.tichu.hand;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Special;
import com.mirboard.domain.game.tichu.card.Suit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 카드 묶음에서 가장 강한 합법 족보를 검출한다.
 *
 * <p>Phoenix 와일드 처리: 카드에 Phoenix 가 포함되면 rank 2..14 의 대체 카드를
 * 하나씩 시도하여 가장 강한 비-BOMB 해석을 선택한다. Phoenix 는 BOMB 또는
 * STRAIGHT_FLUSH_BOMB 의 구성원이 될 수 없다 (룰).
 */
public final class HandDetector {

    private HandDetector() {
    }

    public static Optional<Hand> detect(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return Optional.empty();
        }

        List<Card> sorted = cards.stream()
                .sorted(Comparator.comparingInt(Card::rank))
                .toList();

        // Phoenix-only single is special: phoenixSingle flag set, rank = 0 placeholder.
        if (sorted.size() == 1) {
            Card c = sorted.get(0);
            if (c.is(Special.PHOENIX)) {
                return Optional.of(new Hand(HandType.SINGLE, sorted, 0, 1, true));
            }
            return Optional.of(new Hand(HandType.SINGLE, sorted, c.rank(), 1));
        }

        boolean hasPhoenix = sorted.stream().anyMatch(c -> c.is(Special.PHOENIX));
        if (hasPhoenix) {
            return detectWithPhoenix(sorted);
        }

        // Combos may not contain Dragon or Dog (single-only special cards).
        if (sorted.stream().anyMatch(c -> c.is(Special.DRAGON) || c.is(Special.DOG))) {
            return Optional.empty();
        }

        // Most specific wins. SFB before BOMB before non-bombs.
        return detectStraightFlushBomb(sorted)
                .or(() -> detectBomb(sorted))
                .or(() -> detectFullHouse(sorted))
                .or(() -> detectConsecutivePairs(sorted))
                .or(() -> detectStraight(sorted))
                .or(() -> detectTriple(sorted))
                .or(() -> detectPair(sorted));
    }

    // -------- Phoenix substitution --------

    private static Optional<Hand> detectWithPhoenix(List<Card> sorted) {
        // Phoenix with Dragon/Dog isn't valid; both can't combo.
        if (sorted.stream().anyMatch(c -> c.is(Special.DRAGON) || c.is(Special.DOG))) {
            return Optional.empty();
        }

        // Phoenix in combos: cards must otherwise be normal (Mahjong allowed for straights).
        List<Card> withoutPhoenix = sorted.stream()
                .filter(c -> !c.is(Special.PHOENIX))
                .toList();

        Hand best = null;
        for (int substituteRank = 2; substituteRank <= 14; substituteRank++) {
            List<Card> trial = new ArrayList<>(withoutPhoenix);
            trial.add(Card.normal(Suit.JADE, substituteRank));
            List<Card> trialSorted = trial.stream()
                    .sorted(Comparator.comparingInt(Card::rank))
                    .toList();
            Optional<Hand> detected = detectNonBomb(trialSorted);
            if (detected.isEmpty()) continue;
            Hand candidate = detected.get();
            // Realize with the actual player-held cards (Phoenix in place of synthetic).
            Hand realized = new Hand(candidate.type(), sorted,
                    candidate.rank(), candidate.length(), false);
            if (best == null || strongerThan(realized, best)) {
                best = realized;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Hand> detectNonBomb(List<Card> sorted) {
        return detectFullHouse(sorted)
                .or(() -> detectConsecutivePairs(sorted))
                .or(() -> detectStraight(sorted))
                .or(() -> detectTriple(sorted))
                .or(() -> detectPair(sorted));
    }

    /** Phoenix 대체 후보들 간의 "더 강한" 해석 선택용. 타입 우선순위 → 길이 → rank. */
    private static boolean strongerThan(Hand a, Hand b) {
        int ap = typePriority(a.type());
        int bp = typePriority(b.type());
        if (ap != bp) return ap > bp;
        if (a.length() != b.length()) return a.length() > b.length();
        return a.rank() > b.rank();
    }

    private static int typePriority(HandType t) {
        return switch (t) {
            case STRAIGHT_FLUSH_BOMB -> 7;
            case BOMB -> 6;
            case FULL_HOUSE -> 5;
            case CONSECUTIVE_PAIRS -> 4;
            case STRAIGHT -> 3;
            case TRIPLE -> 2;
            case PAIR -> 1;
            case SINGLE -> 0;
        };
    }

    // -------- Per-type detectors (Phoenix-free; expect sorted cards) --------

    private static Optional<Hand> detectPair(List<Card> sorted) {
        if (sorted.size() != 2) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        int r0 = sorted.get(0).rank();
        if (sorted.get(1).rank() != r0) return Optional.empty();
        return Optional.of(new Hand(HandType.PAIR, sorted, r0, 2));
    }

    private static Optional<Hand> detectTriple(List<Card> sorted) {
        if (sorted.size() != 3) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        int r = sorted.get(0).rank();
        if (!sorted.stream().allMatch(c -> c.rank() == r)) return Optional.empty();
        return Optional.of(new Hand(HandType.TRIPLE, sorted, r, 3));
    }

    private static Optional<Hand> detectFullHouse(List<Card> sorted) {
        if (sorted.size() != 5) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        Map<Integer, Long> byRank = sorted.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));
        if (byRank.size() != 2) return Optional.empty();
        if (!byRank.containsValue(3L) || !byRank.containsValue(2L)) return Optional.empty();
        int tripleRank = byRank.entrySet().stream()
                .filter(e -> e.getValue() == 3L)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        return Optional.of(new Hand(HandType.FULL_HOUSE, sorted, tripleRank, 5));
    }

    private static Optional<Hand> detectStraight(List<Card> sorted) {
        int n = sorted.size();
        if (n < 5) return Optional.empty();
        if (sorted.stream().anyMatch(c -> c.isSpecial() && !c.is(Special.MAHJONG))) {
            return Optional.empty();
        }
        for (int i = 1; i < n; i++) {
            if (sorted.get(i).rank() != sorted.get(i - 1).rank() + 1) {
                return Optional.empty();
            }
        }
        return Optional.of(new Hand(HandType.STRAIGHT, sorted, sorted.get(n - 1).rank(), n));
    }

    private static Optional<Hand> detectConsecutivePairs(List<Card> sorted) {
        int n = sorted.size();
        if (n < 6 || n % 2 != 0) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        Map<Integer, Long> byRank = sorted.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));
        if (!byRank.values().stream().allMatch(c -> c == 2L)) return Optional.empty();
        List<Integer> ranks = byRank.keySet().stream().sorted().toList();
        for (int i = 1; i < ranks.size(); i++) {
            if (ranks.get(i) != ranks.get(i - 1) + 1) return Optional.empty();
        }
        return Optional.of(new Hand(HandType.CONSECUTIVE_PAIRS, sorted,
                ranks.get(ranks.size() - 1), n));
    }

    private static Optional<Hand> detectBomb(List<Card> sorted) {
        if (sorted.size() != 4) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        int r = sorted.get(0).rank();
        if (!sorted.stream().allMatch(c -> c.rank() == r)) return Optional.empty();
        return Optional.of(new Hand(HandType.BOMB, sorted, r, 4));
    }

    private static Optional<Hand> detectStraightFlushBomb(List<Card> sorted) {
        int n = sorted.size();
        if (n < 5) return Optional.empty();
        if (sorted.stream().anyMatch(Card::isSpecial)) return Optional.empty();
        var suit = sorted.get(0).suit();
        if (!sorted.stream().allMatch(c -> c.suit() == suit)) return Optional.empty();
        for (int i = 1; i < n; i++) {
            if (sorted.get(i).rank() != sorted.get(i - 1).rank() + 1) return Optional.empty();
        }
        return Optional.of(new Hand(HandType.STRAIGHT_FLUSH_BOMB, sorted,
                sorted.get(n - 1).rank(), n));
    }
}
