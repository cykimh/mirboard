package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 순수 엔진이 받는 방 설정(`docs/rules-starsquare.md` §3·§9·§10). 어댑터(S2)가 {@code GameContext} 의 좌석·봇 좌석·맵 옵션
 * ({@code MATCH_LENGTH}·{@code DIFFICULTY}·{@code BOT_LEVEL})·턴 제한에서 만든다.
 *
 * @param puzzleCount  퍼즐 수 R(1·3·5)
 * @param limitSeconds 퍼즐 제한(초) — 0 이하가 오면 기본 300초로 본다(§9-2)
 */
public record StarSquareConfig(int seatCount, Set<Integer> botSeats, int puzzleCount, Difficulty difficulty,
                               BotLevel botLevel, int limitSeconds) {

    /** 기본 퍼즐 제한(초) — 5분. */
    public static final int DEFAULT_LIMIT_SECONDS = 300;

    public StarSquareConfig {
        if (seatCount < 1 || seatCount > 4) {
            throw new IllegalArgumentException("Star square needs 1..4 seats: " + seatCount);
        }
        if (puzzleCount < 1) {
            throw new IllegalArgumentException("Puzzle count must be >= 1");
        }
        botSeats = Collections.unmodifiableSet(new TreeSet<>(botSeats == null ? Set.of() : botSeats));
        difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
        botLevel = botLevel == null ? BotLevel.NORMAL : botLevel;
        limitSeconds = limitSeconds <= 0 ? DEFAULT_LIMIT_SECONDS : limitSeconds;
    }

    /** {@code MATCH_LENGTH} 값 → 퍼즐 수(§3-1): 짧게 1 · 보통 3 · 길게 5. 모르는 값은 보통. */
    public static int puzzleCountFor(String matchLength) {
        if ("SHORT".equals(matchLength)) {
            return 1;
        }
        if ("LONG".equals(matchLength)) {
            return 5;
        }
        return 3;
    }

    public long limitMillis() {
        return limitSeconds * 1000L;
    }

    public boolean bot(int seat) {
        return botSeats.contains(seat);
    }
}
