package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 퍼즐 은행의 띠(`docs/rules-starsquare.md` §4.2) — 크기 × 단서 띠. 자원 파일 {@code starsquare/puzzles-{key}.json} 하나씩.
 *
 * @param clueCount 단서 수 [수치] — S1 측정·S1.5 사람 측정으로 확정(§4.1)
 */
public enum Band {
    THREE_EASY("3-easy", 3, 2),
    FOUR_EASY("4-easy", 4, 8),
    FOUR_NORMAL("4-normal", 4, 7),
    FIVE_HARD("5-hard", 5, 12);

    private final String key;
    private final int size;
    private final int clueCount;

    Band(String key, int size, int clueCount) {
        this.key = key;
        this.size = size;
        this.clueCount = clueCount;
    }

    public String key() {
        return key;
    }

    public int size() {
        return size;
    }

    public int clueCount() {
        return clueCount;
    }

    public String resourcePath() {
        return "starsquare/puzzles-" + key + ".json";
    }
}
