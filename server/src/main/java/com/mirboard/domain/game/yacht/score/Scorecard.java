package com.mirboard.domain.game.yacht.score;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 한 좌석의 점수표 (`docs/rules-yacht.md` §5). 채운 칸만 키로 든다 — 빈 칸은 키가 없다(0점으로 버린 칸은 값 0).
 *
 * <p>소계·보너스·총점은 저장하지 않고 칸에서 계산한다(§5.1). {@code isGetterVisibility=NONE} 은 {@link #isFull()} 이
 * JSON 프로퍼티로 새지 않게 하려는 것이다.
 */
@JsonAutoDetect(isGetterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scorecard(Map<Category, Integer> scores) {

    public static final int BONUS_THRESHOLD = 63;
    public static final int BONUS_POINTS = 35;

    public static final Scorecard EMPTY = new Scorecard(Map.of());

    public Scorecard {
        EnumMap<Category, Integer> copy = new EnumMap<>(Category.class);
        if (scores != null) {
            scores.forEach((c, p) -> {
                if (c == null || p == null || p < 0) {
                    throw new IllegalArgumentException("Invalid score entry: " + c + "=" + p);
                }
                copy.put(c, p);
            });
        }
        scores = Collections.unmodifiableMap(copy);
    }

    public boolean filled(Category category) {
        return scores.containsKey(category);
    }

    public int filledCount() {
        return scores.size();
    }

    public boolean isFull() {
        return scores.size() == Category.values().length;
    }

    public Scorecard with(Category category, int points) {
        if (filled(category)) {
            throw new IllegalStateException("Category already filled: " + category);
        }
        EnumMap<Category, Integer> next = new EnumMap<>(Category.class);
        next.putAll(scores);
        next.put(category, points);
        return new Scorecard(next);
    }

    public int upperSubtotal() {
        int sum = 0;
        for (Map.Entry<Category, Integer> e : scores.entrySet()) {
            if (e.getKey().upper()) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    public int bonus() {
        return upperSubtotal() >= BONUS_THRESHOLD ? BONUS_POINTS : 0;
    }

    public int total() {
        int sum = 0;
        for (int p : scores.values()) {
            sum += p;
        }
        return sum + bonus();
    }
}
