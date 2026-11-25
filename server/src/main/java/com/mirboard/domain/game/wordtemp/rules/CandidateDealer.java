package com.mirboard.domain.game.wordtemp.rules;

import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 후보 낱말 나누기(`docs/rules-wordtemp.md` §3.1). 좌석마다 {@code max(3, R+1)} 개 — 한 사람의 후보는 서로 다른 범주, 판 전체에서 겹치지 않고
 * 서로 같은 말 관계도 아니다. 순수 함수(난수기는 인자) — S2 의 라운드 스타터가 은행과 씨앗을 넣어 부른다.
 */
public final class CandidateDealer {

    private CandidateDealer() {
    }

    /** 라운드 수 R 의 후보 수 [결정] — 마지막 라운드에도 고를 것이 2개 남는다. */
    public static int perSeat(int rounds) {
        return Math.max(3, rounds + 1);
    }

    public static List<List<AnswerEntry>> deal(List<AnswerEntry> bank, int seats, int rounds, SplittableRandom rng) {
        int per = perSeat(rounds);
        List<AnswerEntry> pool = new ArrayList<>(bank);
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            AnswerEntry t = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, t);
        }
        Set<String> usedForms = new HashSet<>();
        boolean[] taken = new boolean[pool.size()];
        List<List<AnswerEntry>> hands = new ArrayList<>(seats);
        for (int seat = 0; seat < seats; seat++) {
            List<AnswerEntry> hand = new ArrayList<>(per);
            Set<String> categories = new HashSet<>();
            for (int i = 0; i < pool.size() && hand.size() < per; i++) {
                AnswerEntry e = pool.get(i);
                if (taken[i] || categories.contains(e.category())) {
                    continue;
                }
                Set<String> forms = AnswerForms.of(e);
                if (forms.isEmpty() || forms.stream().anyMatch(usedForms::contains)) {
                    continue;
                }
                taken[i] = true;
                usedForms.addAll(forms);
                categories.add(e.category());
                hand.add(e);
            }
            if (hand.size() < per) {
                throw new IllegalArgumentException("Answer bank too small: seat " + seat + " got " + hand.size()
                        + " of " + per + " candidates (bank " + bank.size() + ")");
            }
            hands.add(List.copyOf(hand));
        }
        return hands;
    }
}
