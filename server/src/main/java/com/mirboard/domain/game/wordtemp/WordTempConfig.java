package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.bot.WordTempBotLevel;
import java.util.Set;

/**
 * 한 판의 설정(`docs/rules-wordtemp.md` §2) — 방에서 정해져 판 내내 바뀌지 않는 것. 상태가 아니라 엔진이 들고 있다(S2 어댑터가 방 정보로 만든다).
 *
 * @param seatCount    좌석 수 3~6
 * @param bots         봇 좌석
 * @param rounds       라운드 수({@code MATCH_LENGTH} — 짧게 1 · 보통 2 · 길게 3)
 * @param hintSeconds  힌트 단계 시간 = 방의 턴 제한(60·90·120·180, 기본 90)
 * @param botLevel     봇 강도({@code BOT_LEVEL})
 * @param liveCap      한 판의 실시간 채점 상한(설계서 §5.5 {@code per-match-cap})
 * @param blockedWords 금칙어 — 이 낱말을 품은 추리 답은 남에게 가려 보인다(§12)
 * @param tuning       조정 수치
 */
public record WordTempConfig(int seatCount,
                             Set<Integer> bots,
                             int rounds,
                             int hintSeconds,
                             WordTempBotLevel botLevel,
                             int liveCap,
                             Set<String> blockedWords,
                             WordTempTuning tuning) {

    public static final int MIN_SEATS = 3;
    public static final int MAX_SEATS = 6;
    public static final int DEFAULT_HINT_SECONDS = 90;
    public static final int DEFAULT_LIVE_CAP = 60;

    public WordTempConfig {
        if (seatCount < MIN_SEATS || seatCount > MAX_SEATS) {
            throw new IllegalArgumentException("Word temperature supports 3-6 seats: " + seatCount);
        }
        if (rounds < 1 || rounds > 3) {
            throw new IllegalArgumentException("rounds must be 1-3: " + rounds);
        }
        if (hintSeconds <= 0) {
            throw new IllegalArgumentException("hint limit cannot be off: " + hintSeconds);
        }
        bots = bots == null ? Set.of() : Set.copyOf(bots);
        botLevel = botLevel == null ? WordTempBotLevel.NORMAL : botLevel;
        blockedWords = blockedWords == null ? Set.of() : Set.copyOf(blockedWords);
        tuning = tuning == null ? WordTempTuning.DEFAULT : tuning;
    }

    /** 흔한 꼴 — 금칙어 없음, 기본 수치, 판 상한 60. */
    public static WordTempConfig of(int seatCount, Set<Integer> bots, int rounds, int hintSeconds,
                                    WordTempBotLevel botLevel) {
        return new WordTempConfig(seatCount, bots, rounds, hintSeconds, botLevel, DEFAULT_LIVE_CAP, Set.of(), null);
    }

    public boolean bot(int seat) {
        return bots.contains(seat);
    }

    public long hintMs() {
        return hintSeconds * 1_000L;
    }

    /** 단계의 길이(§9). 끝난 판은 0. */
    public long phaseMs(com.mirboard.domain.game.wordtemp.state.Phase phase) {
        return switch (phase) {
            case WORD_SELECT -> tuning.selectMs();
            case HINT -> hintMs();
            case GUESS -> tuning.guessMs();
            case ATTEMPT_RESULT -> tuning.resultMs();
            case TURN_END -> tuning.turnEndMs();
            case FINISHED -> 0L;
        };
    }

    public WordTempConfig withTuning(WordTempTuning next) {
        return new WordTempConfig(seatCount, bots, rounds, hintSeconds, botLevel, liveCap, blockedWords, next);
    }

    public WordTempConfig withBlockedWords(Set<String> next) {
        return new WordTempConfig(seatCount, bots, rounds, hintSeconds, botLevel, liveCap, next, tuning);
    }

    public WordTempConfig withLiveCap(int next) {
        return new WordTempConfig(seatCount, bots, rounds, hintSeconds, botLevel, next, blockedWords, tuning);
    }
}
