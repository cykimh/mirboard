package com.mirboard.domain.game.tichu.bot;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.card.Suit;
import com.mirboard.domain.game.tichu.hand.HandType;
import java.util.ArrayList;
import java.util.List;

/**
 * D-118 — 손패를 "낼 묶음"으로 나누는 평가 함수. 봇의 모든 결정 지점(선언·패스·리드·팔로우)이
 * 같은 {@link Plan} 의 묶음 수·컨트롤·루저로 판단한다.
 *
 * <p><b>손패 표현</b>: 56비트 마스크. 비트 번호를 정규 카드 키(rank → suit → special) 순서로
 * 매겨 두어서, 비트를 낮은 쪽부터 읽으면 곧 정규 순서다. 개=0, 봉황=1, 마작=2,
 * 일반 카드 = 3 + (rank−2)·4 + suit, 용=55. 랭크별 4비트 니블이 그 랭크의 무늬 마스크다.
 * 해시 컬렉션을 쓰지 않으므로 순회 순서가 JVM 실행마다 바뀌지 않는다.
 *
 * <p><b>분해 절차</b>: 폭탄(SF → 포카드)을 먼저 예약한 뒤, 네 변형(스트레이트 먼저/세트 먼저 ×
 * 봉황 채우기 여부)으로 그리디 분해하고 (묶음 수, 루저 수) 가 사전순 최소인 것을 고른다.
 * 성능 때문에 이 경로에서는 {@code HandDetector}·{@link ComboFinder} 를 부르지 않는다.
 *
 * <p><b>봉황</b>은 둘 이상의 묶음을 하나로 합칠 때(스트레이트 빈칸·연속페어·풀하우스)만 조합에
 * 쓴다. 단일→페어처럼 하나만 합치는 쓰임은 단독 컨트롤을 잃는 값이 더 커서 하지 않는다.
 */
final class HandPlanner {

    // ---------- 카드 ↔ 비트 ----------

    static final int DOG_BIT = 0;
    static final int PHOENIX_BIT = 1;
    static final int MAHJONG_BIT = 2;
    static final int DRAGON_BIT = 55;

    static final long DOG = 1L << DOG_BIT;
    static final long PHOENIX = 1L << PHOENIX_BIT;
    static final long MAHJONG = 1L << MAHJONG_BIT;
    static final long DRAGON = 1L << DRAGON_BIT;

    private static final Card[] CARDS = buildCards();

    private HandPlanner() {
    }

    static int index(Card c) {
        if (c.special() != null) {
            return switch (c.special()) {
                case DOG -> DOG_BIT;
                case PHOENIX -> PHOENIX_BIT;
                case MAHJONG -> MAHJONG_BIT;
                case DRAGON -> DRAGON_BIT;
            };
        }
        return 3 + (c.rank() - 2) * 4 + c.suit().ordinal();
    }

    static Card card(int index) {
        return CARDS[index];
    }

    static long bit(Card c) {
        return 1L << index(c);
    }

    /** 일반 카드 rank(2..14)·suit 의 비트. */
    static long bit(int rank, int suit) {
        return 1L << (3 + (rank - 2) * 4 + suit);
    }

    static long mask(List<Card> cards) {
        long m = 0;
        for (Card c : cards) {
            m |= bit(c);
        }
        return m;
    }

    /** 마스크의 카드들을 정규 순서로. */
    static List<Card> cards(long mask) {
        List<Card> out = new ArrayList<>(Long.bitCount(mask));
        long m = mask;
        while (m != 0) {
            int i = Long.numberOfTrailingZeros(m);
            out.add(CARDS[i]);
            m &= m - 1;
        }
        return out;
    }

    /** rank(2..14) 의 4비트 무늬 니블. */
    static int nibble(long mask, int rank) {
        return (int) ((mask >>> (3 + (rank - 2) * 4)) & 0xF);
    }

    /** rank 1 = 마작, 2..14 = 일반 카드 장수. */
    static int count(long mask, int rank) {
        if (rank == 1) return (mask & MAHJONG) != 0 ? 1 : 0;
        return Integer.bitCount(nibble(mask, rank));
    }

    static long rankBits(int rank) {
        return rank == 1 ? MAHJONG : 0xFL << (3 + (rank - 2) * 4);
    }

    /**
     * 카드 묶음의 정규 순서: 장수 → 정규 카드 키의 사전순. 모든 선택의 마지막 타이브레이커.
     */
    static int compareCanonical(long a, long b) {
        int sa = Long.bitCount(a);
        int sb = Long.bitCount(b);
        if (sa != sb) return Integer.compare(sa, sb);
        // 장수가 같으면 처음 갈리는 비트(가장 낮은 차이 비트)를 가진 쪽이 앞이다.
        // 비트를 뒤집으면 그 비트가 상위로 가므로 부호 없는 비교 한 번으로 끝난다.
        return Long.compareUnsigned(Long.reverse(b), Long.reverse(a));
    }

    /** 무늬별로 5장 이상 이어진 일반 카드(SF 폭탄 구성원 후보). */
    static long straightFlushMembers(long hand) {
        long members = 0;
        for (int s = 0; s < 4; s++) {
            int runStart = -1;
            for (int r = 2; r <= 15; r++) {
                boolean has = r <= 14 && (hand & bit(r, s)) != 0;
                if (has) {
                    if (runStart < 0) runStart = r;
                } else {
                    if (runStart >= 0 && r - runStart >= 5) {
                        for (int k = runStart; k < r; k++) members |= bit(k, s);
                    }
                    runStart = -1;
                }
            }
        }
        return members;
    }

    // ---------- 분해 ----------

    /**
     * 낼 묶음 하나. 개는 {@code SINGLE} + 개 비트로 표현한다.
     *
     * @param rank 비교 대표값 (단일=카드 rank, 스트레이트·연속페어=최고 rank, 풀하우스=트리플 rank)
     */
    record Group(HandType type, int rank, int length, long mask, boolean control, boolean loser) {
    }

    /**
     * 분해 결과. {@code cost = 묶음 수 + 루저 수} — 작을수록 손을 빨리, 안전하게 비울 수 있다.
     */
    record Plan(List<Group> groups, int controls, int losers) {
        Plan {
            groups = List.copyOf(groups);
        }

        int size() {
            return groups.size();
        }

        int cost() {
            return groups.size() + losers;
        }
    }

    static Plan plan(long hand) {
        Work best = solve(hand, true);
        List<Group> groups = new ArrayList<>(best.out);
        groups.sort((a, b) -> compareCanonical(a.mask(), b.mask()));
        return new Plan(groups, best.controls, best.losers);
    }

    /** {@link #plan} 의 cost 만 — 묶음 객체를 만들지 않는 빠른 경로. */
    static int cost(long hand) {
        Work best = solve(hand, false);
        return best.groups + best.losers;
    }

    /**
     * 컨트롤: 용, 봉황 단독, A 단독, 폭탄, K 이상 페어·연속페어, Q 이상 트리플·풀하우스,
     * A 로 끝나는 스트레이트.
     */
    static boolean isControl(HandType type, int rank, long mask) {
        return switch (type) {
            case SINGLE -> mask == DRAGON || mask == PHOENIX || rank == 14;
            case PAIR, CONSECUTIVE_PAIRS -> rank >= 13;
            case TRIPLE, FULL_HOUSE -> rank >= 12;
            case STRAIGHT -> rank == 14;
            case BOMB, STRAIGHT_FLUSH_BOMB -> true;
        };
    }

    /** 루저: 컨트롤이 아닌 단일·페어 중 rank ≤10, 트리플 중 rank ≤8. 개·4장 이상은 아니다. */
    static boolean isLoser(HandType type, int rank, long mask) {
        if (isControl(type, rank, mask)) return false;
        return switch (type) {
            case SINGLE -> mask != DOG && rank <= 10;
            case PAIR -> rank <= 10;
            case TRIPLE -> rank <= 8;
            default -> false;
        };
    }

    private static Work solve(long hand, boolean record) {
        Work base = new Work(record);
        base.rest = hand & ~(DOG | PHOENIX | DRAGON);
        base.phoenix = (hand & PHOENIX) != 0;
        if ((hand & DRAGON) != 0) base.add(HandType.SINGLE, 100, 1, DRAGON);
        if ((hand & DOG) != 0) base.add(HandType.SINGLE, 0, 1, DOG);
        reserveBombs(base);

        Work best = null;
        // 변형 순서: 세트 먼저 → 스트레이트 먼저, 각각 봉황 미사용 → 사용. 동률이면 앞 변형
        // (= 봉황을 단독 컨트롤로 남기는 쪽)이 남는다.
        for (int v = 0; v < 4; v++) {
            boolean straightFirst = (v & 1) != 0;
            boolean fill = (v & 2) != 0;
            if (fill && !base.phoenix) break;
            Work w = base.copy();
            decompose(w, straightFirst, fill);
            if (best == null || w.groups < best.groups
                    || (w.groups == best.groups && w.losers < best.losers)) {
                best = w;
            }
        }
        return best;
    }

    /** SF(무늬별 5장 이상 연속) → 포카드 순으로 폭탄을 먼저 떼어 둔다. */
    private static void reserveBombs(Work w) {
        for (int s = 0; s < 4; s++) {
            int runStart = -1;
            for (int r = 2; r <= 15; r++) {
                boolean has = r <= 14 && (w.rest & bit(r, s)) != 0;
                if (has) {
                    if (runStart < 0) runStart = r;
                    continue;
                }
                if (runStart >= 0 && r - runStart >= 5) {
                    long m = 0;
                    for (int k = runStart; k < r; k++) m |= bit(k, s);
                    w.rest &= ~m;
                    w.add(HandType.STRAIGHT_FLUSH_BOMB, r - 1, r - runStart, m);
                }
                runStart = -1;
            }
        }
        for (int r = 2; r <= 14; r++) {
            if (count(w.rest, r) == 4) {
                long m = rankBits(r);
                w.rest &= ~m;
                w.add(HandType.BOMB, r, 4, m);
            }
        }
    }

    private static void decompose(Work w, boolean straightFirst, boolean fill) {
        if (straightFirst) takeStraights(w, fill);
        takeConsecutivePairs(w, fill);
        takeFullHouses(w, fill);
        takeSets(w);
        if (!straightFirst) takeStraights(w, fill);
        takeSingles(w);
        if (w.phoenix) {
            w.phoenix = false;
            w.add(HandType.SINGLE, 0, 1, PHOENIX);
        }
    }

    /** 가장 긴 스트레이트부터(동률이면 봉황 미사용 → 낮은 시작) 반복해서 뗀다. */
    private static void takeStraights(Work w, boolean fill) {
        while (true) {
            boolean canFill = fill && w.phoenix;
            int bestLo = -1;
            int bestHi = -1;
            int bestMissing = -1;
            for (int lo = 1; lo <= 10; lo++) {
                int missing = -1;
                for (int hi = lo; hi <= 14; hi++) {
                    if (count(w.rest, hi) == 0) {
                        if (hi == 1 || !canFill || missing >= 0) break;
                        missing = hi;
                    }
                    int len = hi - lo + 1;
                    if (len < 5) continue;
                    int bestLen = bestLo < 0 ? 0 : bestHi - bestLo + 1;
                    if (len > bestLen || (len == bestLen && bestMissing >= 0 && missing < 0)) {
                        bestLo = lo;
                        bestHi = hi;
                        bestMissing = missing;
                    }
                }
            }
            if (bestLo < 0) return;
            long m = 0;
            for (int r = bestLo; r <= bestHi; r++) {
                if (r != bestMissing) m |= lowestCard(w.rest, r);
            }
            w.rest &= ~m;
            if (bestMissing >= 0) {
                m |= PHOENIX;
                w.phoenix = false;
            }
            w.add(HandType.STRAIGHT, bestHi, bestHi - bestLo + 1, m);
        }
    }

    /** 페어(정확히 2장)가 2개 이상 이어진 구간. 봉황은 1장짜리 랭크 하나를 채울 때만. */
    private static void takeConsecutivePairs(Work w, boolean fill) {
        int r = 2;
        while (r <= 14) {
            if (count(w.rest, r) != 2) {
                r++;
                continue;
            }
            int hi = r;
            while (hi + 1 <= 14 && count(w.rest, hi + 1) == 2) hi++;
            if (hi > r) {
                long m = 0;
                for (int k = r; k <= hi; k++) m |= rankBits(k);
                m &= w.rest;
                w.rest &= ~m;
                w.add(HandType.CONSECUTIVE_PAIRS, hi, (hi - r + 1) * 2, m);
            }
            r = hi + 1;
        }
        if (!fill || !w.phoenix) return;
        int bestLo = -1;
        int bestHi = -1;
        for (int lo = 2; lo <= 13; lo++) {
            boolean single = false;
            for (int hi = lo; hi <= 14; hi++) {
                int c = count(w.rest, hi);
                if (c == 1 && !single) {
                    single = true;
                } else if (c != 2) {
                    break;
                }
                if (hi > lo && single && hi - lo > bestHi - bestLo) {
                    bestLo = lo;
                    bestHi = hi;
                }
            }
        }
        if (bestLo < 0) return;
        long m = 0;
        for (int k = bestLo; k <= bestHi; k++) m |= rankBits(k);
        m &= w.rest;
        w.rest &= ~m;
        w.phoenix = false;
        w.add(HandType.CONSECUTIVE_PAIRS, bestHi, (bestHi - bestLo + 1) * 2, m | PHOENIX);
    }

    /**
     * 트리플과 페어를 낮은 것끼리 짝짓는다. 봉황은 트리플+단일 또는 페어+페어를 풀하우스로
     * 합칠 때만 쓴다.
     */
    private static void takeFullHouses(Work w, boolean fill) {
        while (true) {
            int t = lowestWithCount(w.rest, 3);
            int p = lowestWithCount(w.rest, 2);
            if (t < 0 || p < 0) break;
            long m = (rankBits(t) | rankBits(p)) & w.rest;
            w.rest &= ~m;
            w.add(HandType.FULL_HOUSE, t, 5, m);
        }
        if (!fill || !w.phoenix) return;
        int t = lowestWithCount(w.rest, 3);
        if (t >= 0) {
            int s = lowestWithCount(w.rest, 1);
            if (s < 0) return;
            long m = (rankBits(t) | rankBits(s)) & w.rest;
            w.rest &= ~m;
            w.phoenix = false;
            w.add(HandType.FULL_HOUSE, t, 5, m | PHOENIX);
            return;
        }
        int low = lowestWithCount(w.rest, 2);
        if (low < 0) return;
        int high = -1;
        for (int r = 14; r > low; r--) {
            if (count(w.rest, r) == 2) {
                high = r;
                break;
            }
        }
        if (high < 0) return;
        long m = (rankBits(low) | rankBits(high)) & w.rest;
        w.rest &= ~m;
        w.phoenix = false;
        w.add(HandType.FULL_HOUSE, high, 5, m | PHOENIX);
    }

    private static void takeSets(Work w) {
        for (int r = 2; r <= 14; r++) {
            int c = count(w.rest, r);
            if (c < 2) continue;
            long m = rankBits(r) & w.rest;
            w.rest &= ~m;
            w.add(c == 3 ? HandType.TRIPLE : HandType.PAIR, r, c, m);
        }
    }

    private static void takeSingles(Work w) {
        for (int r = 1; r <= 14; r++) {
            while (count(w.rest, r) > 0) {
                long m = lowestCard(w.rest, r);
                w.rest &= ~m;
                w.add(HandType.SINGLE, r, 1, m);
            }
        }
    }

    /** 일반 랭크(2..14) 중 정확히 c 장인 가장 낮은 랭크. 없으면 -1. */
    private static int lowestWithCount(long rest, int c) {
        for (int r = 2; r <= 14; r++) {
            if (count(rest, r) == c) return r;
        }
        return -1;
    }

    private static long lowestCard(long rest, int rank) {
        if (rank == 1) return rest & MAHJONG;
        long bits = rest & rankBits(rank);
        return bits & -bits;
    }

    /** 그리디 한 변형의 진행 상태. 묶음 객체는 record=true 일 때만 만든다. */
    private static final class Work {
        final boolean record;
        final List<Group> out;
        long rest;
        boolean phoenix;
        int groups;
        int controls;
        int losers;

        Work(boolean record) {
            this.record = record;
            this.out = record ? new ArrayList<>(16) : List.of();
        }

        Work copy() {
            Work w = new Work(record);
            if (record) w.out.addAll(out);
            w.rest = rest;
            w.phoenix = phoenix;
            w.groups = groups;
            w.controls = controls;
            w.losers = losers;
            return w;
        }

        void add(HandType type, int rank, int length, long mask) {
            boolean control = isControl(type, rank, mask);
            boolean loser = !control && isLoser(type, rank, mask);
            groups++;
            if (control) controls++;
            if (loser) losers++;
            if (record) out.add(new Group(type, rank, length, mask, control, loser));
        }
    }

    private static Card[] buildCards() {
        Card[] all = new Card[56];
        all[DOG_BIT] = Card.dog();
        all[PHOENIX_BIT] = Card.phoenix();
        all[MAHJONG_BIT] = Card.mahjong();
        all[DRAGON_BIT] = Card.dragon();
        Suit[] suits = Suit.values();
        for (int r = 2; r <= 14; r++) {
            for (int s = 0; s < suits.length; s++) {
                all[3 + (r - 2) * 4 + s] = Card.normal(suits[s], r);
            }
        }
        return all;
    }

}
