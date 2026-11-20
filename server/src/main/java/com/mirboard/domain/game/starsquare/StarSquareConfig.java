package com.mirboard.domain.game.starsquare;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.starsquare.bot.BotLevel;
import com.mirboard.domain.game.starsquare.puzzle.Difficulty;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 순수 엔진이 받는 방 설정(`docs/rules-starsquare.md` §3·§9·§10). 어댑터가 {@code GameContext} 의 좌석·봇 좌석·맵 옵션
 * ({@code MATCH_LENGTH}·{@code DIFFICULTY}·{@code BOT_LEVEL})·턴 제한에서 만든다({@link #from}).
 *
 * @param puzzleCount  퍼즐 수 R(1·3·5)
 * @param limitSeconds 퍼즐 제한(초) — 0 이하(대기실 "자동")면 난이도별 권장 제한({@link Difficulty#autoLimitSeconds()}, §9-2)
 * @param timeScale    준비·첫 완성 뒤 마감·결과 보기·봇 페이스에 곱하는 배율. <b>운영은 1</b> — 봇끼리 도는 통합 테스트만 줄인다
 *                     ({@code mirboard.starsquare.time-scale}, 씨앗포 {@code resolve-cap-millis} 와 같은 손잡이). 퍼즐 제한은 방 설정이라 곱하지 않는다
 */
public record StarSquareConfig(int seatCount, Set<Integer> botSeats, int puzzleCount, Difficulty difficulty,
                               BotLevel botLevel, int limitSeconds, double timeScale) {

    public StarSquareConfig {
        if (seatCount < 1 || seatCount > 4) {
            throw new IllegalArgumentException("Star square needs 1..4 seats: " + seatCount);
        }
        if (puzzleCount < 1) {
            throw new IllegalArgumentException("Puzzle count must be >= 1");
        }
        if (!(timeScale > 0) || timeScale > 1) {
            throw new IllegalArgumentException("timeScale must be in (0, 1]: " + timeScale);
        }
        botSeats = Collections.unmodifiableSet(new TreeSet<>(botSeats == null ? Set.of() : botSeats));
        difficulty = difficulty == null ? Difficulty.NORMAL : difficulty;
        botLevel = botLevel == null ? BotLevel.NORMAL : botLevel;
        limitSeconds = limitSeconds <= 0 ? difficulty.autoLimitSeconds() : limitSeconds;
    }

    /** 운영 시간(배율 1). */
    public StarSquareConfig(int seatCount, Set<Integer> botSeats, int puzzleCount, Difficulty difficulty,
                            BotLevel botLevel, int limitSeconds) {
        this(seatCount, botSeats, puzzleCount, difficulty, botLevel, limitSeconds, 1.0);
    }

    /** 방 설정 → 엔진 설정. 선언한 맵 옵션은 늘 값이 있다(D-176 — {@code RoomOption.resolve}). */
    public static StarSquareConfig from(GameContext context, double timeScale) {
        return new StarSquareConfig(context.seatCount(), new TreeSet<>(context.botSeats()),
                puzzleCountFor(context.option(RoomOption.MATCH_LENGTH)),
                Difficulty.fromOption(context.option(RoomOption.DIFFICULTY)),
                BotLevel.fromOption(context.option(RoomOption.BOT_LEVEL)),
                context.turnSeconds(), timeScale);
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

    public long introMillis() {
        return scaled(StarSquareEngine.INTRO_MS);
    }

    public long closeAfterFirstMillis() {
        return scaled(StarSquareEngine.CLOSE_AFTER_FIRST_MS);
    }

    public long resultMillis() {
        return scaled(StarSquareEngine.RESULT_MS);
    }

    private long scaled(long ms) {
        return Math.max(1, Math.round(ms * timeScale));
    }

    public boolean bot(int seat) {
        return botSeats.contains(seat);
    }
}
