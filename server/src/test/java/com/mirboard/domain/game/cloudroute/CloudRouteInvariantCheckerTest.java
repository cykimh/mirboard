package com.mirboard.domain.game.cloudroute;

import static com.mirboard.domain.game.cloudroute.CloudRouteTables.table;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.cloudroute.board.Letter;
import com.mirboard.domain.game.cloudroute.invariant.CloudRouteInvariantChecker;
import com.mirboard.domain.game.cloudroute.state.CloudRouteState;
import com.mirboard.domain.game.cloudroute.state.Elimination;
import com.mirboard.domain.game.cloudroute.state.Pending;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 불변식 검사기가 실제로 잡는지 — 통과 1건 + 고의 위반. 규칙 하나를 끄면 해당 케이스만 실패해야 한다. */
class CloudRouteInvariantCheckerTest {

    static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("음수 현금", table(2).cash(0, -1).build()),
                Arguments.of("땅 아닌 칸의 주인", table(2).own(0, 3, 0).build()),
                Arguments.of("주인 없는 땅의 건물", withLevels(table(2).build(), 4, 2)),
                Arguments.of("주인 없는 축제", table(2).festival(4).build()),
                Arguments.of("탈락자의 땅", table(3).turn(1).own(0, 4, 0).eliminated(0, Elimination.Reason.BANKRUPT).build()),
                Arguments.of("편지 보존", withDeck(table(2).build(), List.of(Letter.GUST))),
                Arguments.of("차례 좌석이 탈락", table(3).eliminated(0, Elimination.Reason.DESERTED).build()),
                Arguments.of("갇혔는데 굴리기 대기", table(2).trapped(0, 0).pending(new Pending.AwaitPurchase(4, 0)).build()),
                Arguments.of("살 수 없는 구매 결정", table(2).at(0, 4).cash(0, 50).pending(new Pending.AwaitPurchase(4, 0)).build()),
                Arguments.of("틀린 인수가", table(2).at(0, 7).own(1, 7, 1).pending(new Pending.AwaitTakeover(7, 1, 100)).build()),
                Arguments.of("풍등탑 인수", table(2).at(0, 7).own(1, 7, 3).pending(new Pending.AwaitTakeover(7, 1, 600)).build()),
                Arguments.of("이미 날았는데 비행 결정", table(2).at(0, 18).flew()
                        .pending(new Pending.AwaitFlight(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                                19, 20, 21, 22, 23))).build()),
                Arguments.of("세 번째 더블이 남음", table(2).doubles(3).build()));
    }

    private static CloudRouteState withLevels(CloudRouteState s, int tile, int level) {
        List<Integer> levels = new java.util.ArrayList<>(s.levels());
        levels.set(tile, level);
        return new CloudRouteState(s.seats(), s.owners(), levels, s.festivalTile(), s.buoyHolder(), s.letterDeck(),
                s.letterDiscard(), s.startSeat(), s.turnSeat(), s.round(), s.roundLimit(), s.doublesThisTurn(),
                s.rollAgain(), s.flewThisTurn(), s.lockedTiles(), s.lastDice(), s.pending(), s.eliminations(),
                s.version(), s.result());
    }

    private static CloudRouteState withDeck(CloudRouteState s, List<Letter> deck) {
        return new CloudRouteState(s.seats(), s.owners(), s.levels(), s.festivalTile(), s.buoyHolder(), deck,
                s.letterDiscard(), s.startSeat(), s.turnSeat(), s.round(), s.roundLimit(), s.doublesThisTurn(),
                s.rollAgain(), s.flewThisTurn(), s.lockedTiles(), s.lastDice(), s.pending(), s.eliminations(),
                s.version(), s.result());
    }

    @org.junit.jupiter.api.Test
    void a_plain_opening_table_passes() {
        assertThatCode(() -> CloudRouteInvariantChecker.check(table(4).own(1, 7, 2).festival(7).buoy(3).build()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void a_broken_rule_is_caught(String name, CloudRouteState state) {
        assertThatThrownBy(() -> CloudRouteInvariantChecker.check(state)).isInstanceOf(IllegalStateException.class);
    }
}
