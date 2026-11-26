package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import java.util.Set;

/**
 * 방 설정 → 판 설정(S2, 룰 §2). 방의 맵 옵션({@code MATCH_LENGTH}·{@code BOT_LEVEL})과 턴 제한(= 힌트 제한)을 읽어
 * {@link WordTempConfig} 를 만든다. 어댑터와 라운드 스타터가 같은 함수를 쓴다 — 두 곳의 해석이 어긋나지 않게.
 */
public final class WordTempSettings {

    private WordTempSettings() {
    }

    /** {@code MATCH_LENGTH} → 라운드 수: 짧게 1 · 보통 2 · 길게 3. 모르는 값은 보통. */
    public static int roundsFor(String matchLength) {
        if ("SHORT".equals(matchLength)) {
            return 1;
        }
        if ("LONG".equals(matchLength)) {
            return 3;
        }
        return 2;
    }

    /** 힌트 제한(초) — 방의 턴 제한. 0(끔)은 이 게임에 없으므로 기본 90 으로 읽는다(룰 §2 "끔 없음"). */
    public static int hintSecondsFor(int turnSeconds) {
        return turnSeconds > 0 ? turnSeconds : WordTempConfig.DEFAULT_HINT_SECONDS;
    }

    /**
     * @param liveCap      한 판의 실시간 채점 상한({@code mirboard.wordtemp.live-scoring.per-match-cap})
     * @param blockedWords 금칙어(자원)
     * @param timeScale    시간 배율(운영 1.0)
     */
    public static WordTempConfig config(GameContext ctx, int liveCap, Set<String> blockedWords, double timeScale) {
        return new WordTempConfig(ctx.seatCount(), Set.copyOf(ctx.botSeats()),
                roundsFor(ctx.option(RoomOption.MATCH_LENGTH)), hintSecondsFor(ctx.turnSeconds()),
                WordTempBotLevel.fromOption(ctx.option(RoomOption.BOT_LEVEL)), liveCap, blockedWords,
                WordTempTuning.DEFAULT.scaled(timeScale));
    }
}
