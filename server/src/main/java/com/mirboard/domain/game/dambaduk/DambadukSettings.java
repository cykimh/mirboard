package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.dambaduk.bot.DambadukBotLevel;
import java.util.Map;

/**
 * 방 설정 값 → 담바둑 설정 (D-278). 담바둑이 쓰는 방 설정은 봇 난이도({@code BOT_LEVEL}) 하나다. 모르는 값·빈 값은 보통.
 */
public record DambadukSettings(DambadukBotLevel botLevel) {

    public static final DambadukSettings DEFAULT = new DambadukSettings(DambadukBotLevel.NORMAL);

    public static DambadukSettings from(Map<RoomOption, String> options) {
        if (options == null) {
            return DEFAULT;
        }
        return new DambadukSettings(DambadukBotLevel.parse(options.get(RoomOption.BOT_LEVEL)));
    }
}
