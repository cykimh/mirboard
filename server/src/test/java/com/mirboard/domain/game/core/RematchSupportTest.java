package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import org.junit.jupiter.api.Test;

/**
 * D-122 — 리매치('한 판 더', D-82) 지원은 게임이 선언한다. 기본은 false(옵트인, D-106 과 같은
 * 원칙) — 새 게임은 한 줄도 안 써야 맞게 동작한다: 정상 종료한 방이 FINISHED 로 넘어간다.
 *
 * <p>정의는 저장소를 생성자로 받지만 이 메서드는 쓰지 않으므로 null 로 진짜 인스턴스를 만든다
 * ({@code RoomOptionGatingTest} 와 같은 방식).
 */
class RematchSupportTest {

    @Test
    void default_is_no_rematch() {
        GameDefinition bare = new GameDefinition() {
            @Override public String id() { return "BARE"; }
            @Override public String displayName() { return "BARE"; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 4; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(bare.supportsRematch()).isFalse();
    }

    @Test
    void tichu_supports_rematch() {
        assertThat(new TichuGameDefinition(null, null, null, null).supportsRematch()).isTrue();
    }

    @Test
    void skull_king_does_not_support_rematch() {
        assertThat(new SkullKingGameDefinition(null, null, null).supportsRematch()).isFalse();
    }
}
