package com.mirboard.domain.game.starsquare.puzzle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 매치 시작 때 퍼즐 R개를 한꺼번에 고른다(`docs/rules-starsquare.md` §3-3·§4.2). 퍼즐 번호마다 난이도가 정한 띠에서, 같은 매치에서
 * 같은 원본을 두 번 쓰지 않고(띠가 다 떨어진 경우만 다시 씀), 각각 대칭 변환 16가지 중 하나를 입힌다.
 *
 * <p>난수는 받은 것을 쓴다 — 운영은 어댑터가 SecureRandom 을 넣고(S2), 테스트는 시드를 넣는다.
 */
public final class PuzzlePicker {

    private PuzzlePicker() {
    }

    public static List<PuzzleSpec> pick(PuzzleBank bank, Difficulty difficulty, int count, RandomGenerator rng) {
        if (count < 1) {
            throw new IllegalArgumentException("Puzzle count must be >= 1");
        }
        Map<Band, Set<Integer>> used = new EnumMap<>(Band.class);
        List<PuzzleSpec> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Band band = difficulty.bandFor(i);
            List<PuzzleSpec> pool = bank.puzzles(band);
            if (pool.isEmpty()) {
                throw new IllegalStateException("No puzzles in band " + band.key());
            }
            Set<Integer> taken = used.computeIfAbsent(band, b -> new HashSet<>());
            if (taken.size() >= pool.size()) {
                taken.clear();
            }
            int pick;
            do {
                pick = rng.nextInt(pool.size());
            } while (taken.contains(pick));
            taken.add(pick);
            out.add(pool.get(pick).transformed(new Symmetry(rng.nextInt(Symmetry.COUNT))));
        }
        return List.copyOf(out);
    }
}
