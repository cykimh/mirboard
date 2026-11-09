package com.mirboard.domain.game.omok;

import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.omok.bot.BotLevel;
import java.util.Map;

/**
 * 방 설정 값 → 오목 설정 (D-176·D-177). 값은 {@code RoomOption.resolve} 가 이미 허용값·기본값으로 맞춰 오지만, 모르는 값·빈
 * 값이 와도 기본(1:1·렌주·보통)으로 떨어진다 — 엔진이 설정 하나로 멈추지 않게.
 */
public record OmokSettings(OmokMode mode, OmokRule rule, BotLevel botLevel) {

    public static final OmokSettings DEFAULT = new OmokSettings(OmokMode.SOLO, OmokRule.RENJU, BotLevel.NORMAL);

    public static OmokSettings from(Map<RoomOption, String> options) {
        if (options == null) {
            return DEFAULT;
        }
        return new OmokSettings(
                parse(OmokMode.class, options.get(RoomOption.TEAM_MODE), OmokMode.SOLO),
                parse(OmokRule.class, options.get(RoomOption.OMOK_RULE), OmokRule.RENJU),
                BotLevel.parse(options.get(RoomOption.BOT_LEVEL)));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) {
                return e;
            }
        }
        return fallback;
    }
}
