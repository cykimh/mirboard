package com.mirboard.domain.game.tichu.bot;

import static com.mirboard.domain.game.tichu.bot.HandPlanner.DOG;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.DRAGON;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.MAHJONG;
import static com.mirboard.domain.game.tichu.bot.HandPlanner.PHOENIX;

import com.mirboard.domain.game.tichu.card.Card;
import com.mirboard.domain.game.tichu.hand.Hand;
import com.mirboard.domain.game.tichu.hand.HandType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D-118 — 손패에서 낼 수 있는 카드 묶음 후보를 만든다 (순수).
 *
 * <p>단일·페어·트리플·풀하우스·스트레이트(5~14장, 마작=1)·연속페어(2쌍 이상) — 각각 봉황
 * 1장으로 빈칸을 채우거나 끝을 늘린 변형 포함 — 와 포카드·SF 폭탄. 족보 판정은 여기서 하지
 * 않는다. 최종 판정은 {@code HandDetector}, 합법성은 {@code ActionValidator} 몫이다.
 *
 * <p>같은 랭크는 대표 카드 1장(또는 필요한 장수)만 쓴다. SF 구성원이 아닌 카드를 먼저 고르고,
 * 일반 스트레이트가 우연히 한 무늬가 되어 SF 폭탄으로 판정되지 않도록 다른 무늬 카드로
 * 바꾼다. 바꿀 카드가 없으면 그 5장 이상은 폭탄으로만 나온다.
 *
 * <p>출력은 정규 순서({@link HandPlanner#compareCanonical})로 정렬되고 중복이 없다 — 입력
 * 손패 리스트의 순서와 무관하다.
 */
final class ComboFinder {

    private ComboFinder() {
    }

    /** 리드에서 낼 수 있는 모든 묶음 후보. */
    static List<List<Card>> lead(List<Card> hand) {
        return toCards(leadMasks(HandPlanner.mask(hand)));
    }

    /** 팔로우: top 과 같은 타입·길이 + (top 이 단일이면) 봉황 단독 + 폭탄. */
    static List<List<Card>> follow(List<Card> hand, Hand top) {
        return toCards(followMasks(HandPlanner.mask(hand), top));
    }

    static long[] leadMasks(long hand) {
        Acc acc = new Acc();
        generate(hand, null, acc);
        return acc.sortedUnique();
    }

    static long[] followMasks(long hand, Hand top) {
        Acc acc = new Acc();
        generate(hand, top, acc);
        return acc.sortedUnique();
    }

    private static List<List<Card>> toCards(long[] masks) {
        List<List<Card>> out = new ArrayList<>(masks.length);
        for (long m : masks) {
            out.add(HandPlanner.cards(m));
        }
        return out;
    }

    // ---------- 생성 ----------

    private static void generate(long hand, Hand top, Acc acc) {
        HandType want = top == null ? null : top.type();
        int wantLength = top == null ? 0 : top.length();
        boolean phoenix = (hand & PHOENIX) != 0;
        long sf = HandPlanner.straightFlushMembers(hand);

        boolean all = want == null;
        if (all || want == HandType.SINGLE) singles(hand, sf, all, acc);
        if (all || want == HandType.PAIR) sets(hand, sf, phoenix, 2, acc);
        if (all || want == HandType.TRIPLE) sets(hand, sf, phoenix, 3, acc);
        if (all || want == HandType.FULL_HOUSE) fullHouses(hand, sf, phoenix, acc);
        if (all || want == HandType.STRAIGHT) straights(hand, sf, phoenix, all ? 0 : wantLength, acc);
        if (all || want == HandType.CONSECUTIVE_PAIRS) {
            consecutivePairs(hand, sf, phoenix, all ? 0 : wantLength, acc);
        }
        bombs(hand, top, acc);
    }

    private static void singles(long hand, long sf, boolean lead, Acc acc) {
        // 개는 리드 전용이라 팔로우 후보에서 뺀다.
        for (long special : new long[] {DOG, PHOENIX, MAHJONG, DRAGON}) {
            if ((hand & special) != 0 && (lead || special != DOG)) acc.add(special);
        }
        for (int r = 2; r <= 14; r++) {
            long rep = reps(hand, sf, r, 1);
            if (rep != 0) acc.add(rep);
        }
    }

    /** size=2 페어, size=3 트리플. 한 장 모자라면 봉황으로 채운다. */
    private static void sets(long hand, long sf, boolean phoenix, int size, Acc acc) {
        for (int r = 2; r <= 14; r++) {
            int cnt = HandPlanner.count(hand, r);
            if (cnt >= size) {
                acc.add(reps(hand, sf, r, size));
            } else if (phoenix && cnt == size - 1 && cnt > 0) {
                acc.add(reps(hand, sf, r, cnt) | PHOENIX);
            }
        }
    }

    private static void fullHouses(long hand, long sf, boolean phoenix, Acc acc) {
        for (int t = 2; t <= 14; t++) {
            int ct = HandPlanner.count(hand, t);
            long triple;
            boolean tripleUsesPhoenix;
            if (ct >= 3) {
                triple = reps(hand, sf, t, 3);
                tripleUsesPhoenix = false;
            } else if (ct == 2 && phoenix) {
                triple = reps(hand, sf, t, 2) | PHOENIX;
                tripleUsesPhoenix = true;
            } else {
                continue;
            }
            for (int p = 2; p <= 14; p++) {
                if (p == t) continue;
                int cp = HandPlanner.count(hand, p);
                if (cp >= 2) {
                    acc.add(triple | reps(hand, sf, p, 2));
                } else if (cp == 1 && phoenix && !tripleUsesPhoenix) {
                    acc.add(triple | reps(hand, sf, p, 1) | PHOENIX);
                }
            }
        }
    }

    /** length=0 이면 5장 이상 전부, 아니면 그 길이만. */
    private static void straights(long hand, long sf, boolean phoenix, int length, Acc acc) {
        for (int lo = 1; lo <= 10; lo++) {
            for (int hi = lo + 4; hi <= 14; hi++) {
                int len = hi - lo + 1;
                if (length != 0 && len != length) continue;
                int missing = 0;
                int missingRank = -1;
                for (int r = lo; r <= hi && missing <= 1; r++) {
                    if (HandPlanner.count(hand, r) == 0) {
                        missing++;
                        missingRank = r;
                    }
                }
                if (missing > 1) continue;
                if (missing == 1 && (!phoenix || missingRank == 1)) continue;
                long m = missing == 1 ? PHOENIX : 0;
                for (int r = lo; r <= hi; r++) {
                    if (r == missingRank) continue;
                    m |= r == 1 ? MAHJONG : reps(hand, sf, r, 1);
                }
                if (missing == 0 && lo >= 2) {
                    m = avoidFlush(hand, m, lo, hi);
                    if (m == 0) continue;   // 한 무늬뿐 — 폭탄으로만 나온다.
                }
                acc.add(m);
            }
        }
    }

    /**
     * 일반 카드만의 스트레이트가 한 무늬면 한 랭크를 다른 무늬 카드로 바꾼다. 바꿀 수 없으면 0.
     */
    private static long avoidFlush(long hand, long straight, int lo, int hi) {
        int suits = 0xF;
        for (int r = lo; r <= hi; r++) {
            suits &= HandPlanner.nibble(straight, r);
        }
        if (suits == 0) return straight;   // 이미 무늬가 섞였다.
        for (int r = lo; r <= hi; r++) {
            int others = HandPlanner.nibble(hand, r) & ~suits;
            if (others != 0) {
                int alt = Integer.numberOfTrailingZeros(others);
                return (straight & ~HandPlanner.rankBits(r)) | HandPlanner.bit(r, alt);
            }
        }
        return 0;
    }

    private static void consecutivePairs(long hand, long sf, boolean phoenix, int length, Acc acc) {
        for (int lo = 2; lo <= 13; lo++) {
            long m = 0;
            boolean usedPhoenix = false;
            for (int hi = lo; hi <= 14; hi++) {
                int cnt = HandPlanner.count(hand, hi);
                if (cnt >= 2) {
                    m |= reps(hand, sf, hi, 2);
                } else if (cnt == 1 && phoenix && !usedPhoenix) {
                    m |= reps(hand, sf, hi, 1) | PHOENIX;
                    usedPhoenix = true;
                } else {
                    break;
                }
                int pairs = hi - lo + 1;
                if (pairs >= 2 && (length == 0 || pairs * 2 == length)) acc.add(m);
            }
        }
    }

    /** 팔로우(top != null)면 top 을 이기는 폭탄만. */
    private static void bombs(long hand, Hand top, Acc acc) {
        boolean topIsSf = top != null && top.type() == HandType.STRAIGHT_FLUSH_BOMB;
        int topQuadRank = top != null && top.type() == HandType.BOMB ? top.rank() : 0;
        if (!topIsSf) {
            for (int r = topQuadRank + 1; r <= 14; r++) {
                if (r >= 2 && HandPlanner.count(hand, r) == 4) acc.add(HandPlanner.rankBits(r));
            }
        }
        for (int s = 0; s < 4; s++) {
            int runStart = -1;
            for (int r = 2; r <= 15; r++) {
                boolean has = r <= 14 && (hand & HandPlanner.bit(r, s)) != 0;
                if (has) {
                    if (runStart < 0) runStart = r;
                    continue;
                }
                if (runStart >= 0) {
                    for (int a = runStart; a + 4 < r; a++) {
                        long m = 0;
                        for (int b = a; b < r; b++) {
                            m |= HandPlanner.bit(b, s);
                            int len = b - a + 1;
                            if (len >= 5 && (!topIsSf || beatsSf(len, b, top))) acc.add(m);
                        }
                    }
                }
                runStart = -1;
            }
        }
    }

    private static boolean beatsSf(int length, int topRank, Hand top) {
        return length != top.length() ? length > top.length() : topRank > top.rank();
    }

    /**
     * rank 의 대표 카드 k 장 마스크. SF 구성원이 아닌 카드 → SF 구성원 순, 각각 무늬 순.
     * 장수가 모자라면 0.
     */
    static long reps(long hand, long sfMembers, int rank, int k) {
        int nib = HandPlanner.nibble(hand, rank);
        if (Integer.bitCount(nib) < k) return 0;
        int sfNib = HandPlanner.nibble(sfMembers, rank);
        long out = 0;
        int taken = 0;
        for (int pass = 0; pass < 2 && taken < k; pass++) {
            int pool = pass == 0 ? nib & ~sfNib : nib & sfNib;
            while (pool != 0 && taken < k) {
                int s = Integer.numberOfTrailingZeros(pool);
                out |= HandPlanner.bit(rank, s);
                pool &= pool - 1;
                taken++;
            }
        }
        return out;
    }

    /** 마스크 누적기 — 정렬 후 중복 제거. 해시 컬렉션을 쓰지 않는다. */
    private static final class Acc {
        private long[] items = new long[64];
        private int size;

        void add(long m) {
            if (m == 0) return;
            if (size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = m;
        }

        long[] sortedUnique() {
            Long[] boxed = new Long[size];
            for (int i = 0; i < size; i++) boxed[i] = items[i];
            Arrays.sort(boxed, HandPlanner::compareCanonical);
            long[] out = new long[size];
            int n = 0;
            for (Long m : boxed) {
                if (n == 0 || out[n - 1] != m) out[n++] = m;
            }
            return Arrays.copyOf(out, n);
        }
    }
}
