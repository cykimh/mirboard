package com.mirboard.domain.lobby.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameDefinition;
import com.mirboard.domain.game.core.GameEngine;
import com.mirboard.domain.game.core.GameStatus;
import com.mirboard.domain.game.core.RoomOption;
import com.mirboard.domain.game.skullking.SkullKingGameDefinition;
import com.mirboard.domain.game.tichu.TichuGameDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-106 — 게임이 자기가 쓰는 방 설정을 선언한다.
 *
 * <p>여기서 보는 것은 <b>선언</b>이다. 실제 거절 경로(`UNSUPPORTED_ROOM_OPTION`)는
 * `RoomControllerIntegrationTest` 가 REST 로 확인한다.
 *
 * <p>게임 정의는 저장소를 생성자로 받지만 {@code supportedRoomOptions()} 는 그것들을 쓰지
 * 않으므로 null 로 넘겨 진짜 인스턴스를 만든다 — 리플렉션으로 "재정의했는가"만 보면 기대값을
 * 코드에 두 번 적는 꼴이라 아무것도 검증하지 못한다. Docker 도 컨텍스트도 필요 없다.
 */
class RoomOptionGatingTest {

    /** 포트의 default 만 쓰는 최소 정의 — 새 게임 저자가 실제로 쓰는 형태. */
    private static GameDefinition bareGame(String id) {
        return new GameDefinition() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public String shortDescription() { return ""; }
            @Override public int minPlayers() { return 2; }
            @Override public int maxPlayers() { return 4; }
            @Override public GameStatus status() { return GameStatus.AVAILABLE; }
            @Override public GameEngine newEngine(GameContext ctx) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @DisplayName("기본값은 빈 집합 — 새 게임은 한 줄도 안 써도 안 쓰는 옵션이 안 뜬다")
    void defaultIsOptIn() {
        assertThat(bareGame("BARE").supportedRoomOptions()).isEmpty();
    }

    @Test
    @DisplayName("기본 집합을 건드려도 다른 게임에 새지 않는다 (호출마다 새 인스턴스)")
    void defaultSetIsNotShared() {
        GameDefinition a = bareGame("A");
        GameDefinition b = bareGame("B");

        // EnumSet 은 가변이다. 상수 하나를 공유했다면 여기서 b 까지 오염된다.
        a.supportedRoomOptions().add(RoomOption.BETTING);

        assertThat(b.supportedRoomOptions()).isEmpty();
        assertThat(a.supportedRoomOptions()).isEmpty(); // a 도 매번 새로 만들어 돌려준다
    }

    @Test
    @DisplayName("티츄는 세 옵션을 모두 선언한다")
    void tichuDeclaresAll() {
        var tichu = new TichuGameDefinition(null, null, null, null);

        assertThat(tichu.supportedRoomOptions())
                .containsExactlyInAnyOrder(
                        RoomOption.TARGET_SCORE, RoomOption.TEAMS, RoomOption.BETTING);
    }

    @Test
    @DisplayName("스컬킹은 아무 옵션도 선언하지 않는다 — 10R 고정·개인전·칩 미지원")
    void skullKingDeclaresNone() {
        var skullKing = new SkullKingGameDefinition(null, null);

        assertThat(skullKing.supportedRoomOptions()).isEmpty();
    }

    @Test
    @DisplayName("BETTING 은 티츄만 — RoomChipService 가 티츄를 직접 참조하는 예외와 일치")
    void bettingIsTichuOnly() {
        assertThat(new TichuGameDefinition(null, null, null, null).supportedRoomOptions())
                .contains(RoomOption.BETTING);
        assertThat(new SkullKingGameDefinition(null, null).supportedRoomOptions())
                .doesNotContain(RoomOption.BETTING);
    }

    @Test
    @DisplayName("예외는 어떤 게임의 어떤 옵션이 막혔는지 담는다")
    void exceptionCarriesContext() {
        var e = new UnsupportedRoomOptionException("SKULL_KING", RoomOption.BETTING);

        assertThat(e.gameType()).isEqualTo("SKULL_KING");
        assertThat(e.option()).isEqualTo(RoomOption.BETTING);
        assertThatThrownBy(() -> { throw e; })
                .hasMessageContaining("SKULL_KING")
                .hasMessageContaining("BETTING");
    }
}
