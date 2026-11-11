package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.artillery.ArtilleryGameEngine;
import com.mirboard.domain.game.cloudroute.CloudRouteGameEngine;
import com.mirboard.domain.game.omok.OmokGameEngine;
import com.mirboard.domain.game.onecard.OneCardGameEngine;
import com.mirboard.domain.game.skullking.SkullKingGameEngine;
import com.mirboard.domain.game.tichu.TichuGameEngine;
import com.mirboard.domain.game.yacht.YachtGameEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-229 — 포트 ⑧ 미리보기는 옵트인이다. 기본 구현은 아무것도 내지 않고, 씨앗포 말고는 어느 어댑터도 덮어쓰지 않는다(다른 여섯 게임의 동작 0
 * 변화 — `docs/plans/artillery-s6.md` §5.1 근거 ①②).
 */
class GameEnginePreviewDefaultTest {

    @Test
    void the_default_preview_is_empty() {
        GameEngine engine = mock(GameEngine.class, CALLS_REAL_METHODS);
        assertThat(engine.preview(mock(GameState.class), 0, mock(GameAction.class))).isEmpty();
        assertThat(engine.preview(null, -1, null)).isEmpty();
    }

    @Test
    void only_artillery_overrides_preview() throws Exception {
        for (Class<?> adapter : List.of(TichuGameEngine.class, SkullKingGameEngine.class, OneCardGameEngine.class,
                YachtGameEngine.class, OmokGameEngine.class, CloudRouteGameEngine.class)) {
            assertThatThrownBy(() -> adapter.getDeclaredMethod("preview", GameState.class, int.class, GameAction.class))
                    .as(adapter.getSimpleName())
                    .isInstanceOf(NoSuchMethodException.class);
        }
        assertThat(ArtilleryGameEngine.class.getDeclaredMethod("preview", GameState.class, int.class, GameAction.class))
                .isNotNull();
    }
}
