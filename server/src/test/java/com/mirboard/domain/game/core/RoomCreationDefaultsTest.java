package com.mirboard.domain.game.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mirboard.domain.game.onecard.OneCardGameDefinition;
import com.mirboard.domain.game.onecard.persistence.OneCardStateStore;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-130 — 방 만들기의 처음 선택(인원·턴 제한)도 게임이 선언한다. 기본은 지금까지의 동작 그대로 — 인원은 최대, 턴 제한은
 * 끔 — 이라 티츄·스컬킹은 한 줄도 바꾸지 않는다. 원카드만 4명·30초(설계 §3.1 의 기본 4, 버티기 대응 — 사용자 결정).
 *
 * <p>D-131 — 이 값은 서버의 생략 기본이기도 하다({@code RoomService} — {@code RoomServiceCreateDefaultsTest}). 정의는
 * 저장소를 생성자로 받지만 이 메서드는 쓰지 않으므로 null
 * (원카드는 모의 저장소)로 인스턴스를 만든다({@link RematchSupportTest} 와 같은 방식).
 */
class RoomCreationDefaultsTest {

    @Test
    void defaults_are_the_largest_table_without_a_turn_limit() {
        GameDefinition bare = new GameDefinition() {
            @Override public String id() { return "BARE"; }
            @Override public String displayName() { return "BARE"; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 5; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(bare.defaultPlayers()).isEqualTo(5);
        assertThat(bare.defaultTurnSeconds()).isZero();
    }

    @Test
    void tichu_keeps_four_seats_without_a_turn_limit() {
        TichuGameDefinition tichu = new TichuGameDefinition(null, null, null, null);

        assertThat(tichu.defaultPlayers()).isEqualTo(4);
        assertThat(tichu.defaultTurnSeconds()).isZero();
    }

    @Test
    void skull_king_keeps_eight_seats_without_a_turn_limit() {
        SkullKingGameDefinition skullKing = new SkullKingGameDefinition(null, null, null);

        assertThat(skullKing.defaultPlayers()).isEqualTo(8);
        assertThat(skullKing.defaultTurnSeconds()).isZero();
    }

    /**
     * 선언값은 모달이 실제로 고를 수 있는 값이어야 한다 — 인원은 그 게임의 범위 안, 턴 제한은 모달 선택지(끔·30·60·90초) 중
     * 하나. 밖이면 모달이 아무것도 고르지 않은 채 열리거나 서버가 인원을 거절한다.
     */
    @Test
    void every_declared_choice_is_one_the_modal_can_pick() {
        List<GameDefinition> definitions = List.of(
                new TichuGameDefinition(null, null, null, null),
                new SkullKingGameDefinition(null, null, null),
                new OneCardGameDefinition(mock(OneCardStateStore.class), Clock.systemUTC(), event -> { },
                        GameStatus.COMING_SOON, 3_000, 1_000, 2_500, 1_000, 2_500));

        assertThat(definitions).allSatisfy(def -> {
            assertThat(def.defaultPlayers()).as(def.id()).isBetween(def.minPlayers(), def.maxPlayers());
            assertThat(def.defaultTurnSeconds()).as(def.id()).isIn(0, 30, 60, 90);
        });
    }
}
