package com.mirboard.domain.game.onecard.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mirboard.domain.game.onecard.OneCardEngine;
import com.mirboard.domain.game.onecard.card.Deck;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.rules.Ranking;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.MatchResult.EndReason;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** `docs/rules-onecard.md` §14 — 불변식 검사기가 깨진 상태를 실제로 잡는가. */
class OneCardInvariantCheckerTest {

    private static final PlayingCard TOP = PlayingCard.of(Suit.HEART, 5);

    /** 좌석 0·1 에 각자 3장, 나머지는 뽑을 더미, 맨 위는 ♥5. */
    private static OneCardState valid() {
        return table(2);
    }

    /** 좌석마다 3장, 나머지는 뽑을 더미, 맨 위는 ♥5, 차례는 좌석 0. */
    private static OneCardState table(int seatCount) {
        List<PlayingCard> rest = new ArrayList<>(Deck.all());
        rest.remove(TOP);
        List<List<PlayingCard>> hands = new ArrayList<>();
        for (int seat = 0; seat < seatCount; seat++) {
            hands.add(new ArrayList<>(rest.subList(seat * 3, seat * 3 + 3)));
        }
        List<PlayingCard> pile = new ArrayList<>(rest.subList(seatCount * 3, rest.size()));
        return new OneCardState(hands, pile, List.of(TOP), 0, 1, null, 0, null, List.of(), 0, 0, 1, null);
    }

    private static OneCardState with(OneCardState s, List<PlayingCard> drawPile, int attackStack,
                                     List<Elimination> eliminations, int turnSeat) {
        return new OneCardState(s.hands(), drawPile, s.discardPile(), turnSeat, s.direction(), s.declaredSuit(),
                attackStack, s.race(), eliminations, s.passStreak(), s.turnCount(), s.version(), s.result());
    }

    /** {@code valid()} 에서 시작해 필드를 바꿔 가며 위반을 한 곳씩 만든다. 카드를 옮길 때는 54장 보존을 지킨다. */
    private static final class Draft {
        final List<List<PlayingCard>> hands = new ArrayList<>();
        final List<PlayingCard> drawPile;
        final List<PlayingCard> discardPile;
        final List<Elimination> eliminations;
        int turnSeat;
        int direction;
        Suit declaredSuit;
        int attackStack;
        RaceWindow race;
        int passStreak;
        int turnCount;
        MatchResult result;

        Draft(OneCardState s) {
            s.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
            drawPile = new ArrayList<>(s.drawPile());
            discardPile = new ArrayList<>(s.discardPile());
            eliminations = new ArrayList<>(s.eliminations());
            turnSeat = s.turnSeat();
            direction = s.direction();
            declaredSuit = s.declaredSuit();
            attackStack = s.attackStack();
            race = s.race();
            passStreak = s.passStreak();
            turnCount = s.turnCount();
            result = s.result();
        }

        /** 필드 하나를 바꾸는 한 줄짜리 편집. */
        Draft also(Consumer<Draft> change) {
            change.accept(this);
            return this;
        }

        /** 뽑을 더미의 카드를 그 좌석 손패로 옮긴다. */
        Draft give(int seat, int count) {
            for (int i = 0; i < count; i++) {
                hands.get(seat).add(drawPile.removeLast());
            }
            return this;
        }

        /** 그 좌석의 손패를 모두 뽑을 더미 맨 아래로 — 탈락 기록은 남기지 않는다. */
        Draft emptyHand(int seat) {
            drawPile.addAll(hands.get(seat));
            hands.get(seat).clear();
            return this;
        }

        /** 그 좌석에 카드를 {@code count} 장만 남긴다. */
        Draft keep(int seat, int count) {
            while (hands.get(seat).size() > count) {
                drawPile.add(hands.get(seat).removeLast());
            }
            return this;
        }

        /** §10 — 손패는 더미로 가고 탈락 기록이 남는다. */
        Draft eliminate(int seat) {
            int held = hands.get(seat).size();
            emptyHand(seat);
            eliminations.add(new Elimination(seat, Elimination.Reason.DESERTED, held));
            return this;
        }

        /** 끝난 판으로 만든다 — 순위는 {@link Ranking} 이 지금 손패·탈락으로 매긴다. 차례·창은 비운다. */
        Draft endedAs(EndReason reason, int finisher) {
            turnSeat = -1;
            race = null;
            result = new MatchResult(reason, Ranking.rank(hands, eliminations, finisher));
            return this;
        }

        OneCardState build() {
            return new OneCardState(hands, drawPile, discardPile, turnSeat, direction, declaredSuit, attackStack,
                    race, eliminations, passStreak, turnCount, 1, result);
        }
    }

    private static Draft draft() {
        return new Draft(valid());
    }

    private static Arguments broken(String what, Draft draft, String message) {
        return Arguments.of(what, draft.build(), message);
    }

    @Test
    void a_valid_table_passes() {
        assertThatCode(() -> OneCardInvariantChecker.check(valid())).doesNotThrowAnyException();
    }

    @Test
    void a_lost_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile().subList(1, s.drawPile().size()), 0, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("card conservation");
    }

    @Test
    void an_eliminated_seat_holding_cards_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(new Elimination(1, Elimination.Reason.DESERTED, 3)), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("still holds cards");
    }

    @Test
    void a_pending_attack_on_a_non_attack_card_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 2, List.of(), 0);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("attack pending");
    }

    @Test
    void a_turn_on_a_missing_seat_is_caught() {
        OneCardState s = valid();
        OneCardState broken = with(s, s.drawPile(), 0, List.of(), 5);

        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken)).hasMessageContaining("turn seat");
    }

    /**
     * 한 번에 한 규칙만 어긴 상태 — 메시지가 그 규칙의 것이어야 검사가 의도한 분기에서 걸린 것이다. 앞쪽 여섯은
     * 카드·방향·종료 상태 검사, 나머지는 종료 판정 검사(§11.1, §11.3)다.
     */
    static Stream<Arguments> brokenTables() {
        return Stream.of(
                broken("음수 공격 누적", draft().also(d -> d.attackStack = -1), "negative attack stack"),
                broken("7 이 아닌데 지정 무늬", draft().also(d -> d.declaredSuit = Suit.SPADE), "declared suit"),
                broken("방향 0", draft().also(d -> d.direction = 0), "direction must be"),
                broken("20장을 든 생존자", draft().give(0, 17), "should have gone bankrupt"),
                broken("끝났는데 차례가 남음", draft().endedAs(EndReason.STALEMATE, -1).also(d -> {
                    d.turnCount = OneCardEngine.TURN_LIMIT;
                    d.turnSeat = 0;
                }), "still has a turn or a race"),
                broken("끝났는데 창이 남음", draft().endedAs(EndReason.STALEMATE, -1).also(d -> {
                    d.turnCount = OneCardEngine.TURN_LIMIT;
                    d.race = new RaceWindow(2, 0, 0, 0, 0, 0, 3_000, 1, null);
                }), "still has a turn or a race"),

                broken("차례 상한을 넘음", draft().endedAs(EndReason.STALEMATE, -1)
                        .also(d -> d.turnCount = OneCardEngine.TURN_LIMIT + 1), "is above the limit"),
                broken("열린 판에 생존자 1명", draft().eliminate(1), "an open match needs at least two live seats"),
                broken("열린 판에 연속 패스가 생존자 수", draft().also(d -> d.passStreak = 2), "pass streak 2 should have ended"),
                broken("열린 판이 차례 상한", draft().also(d -> d.turnCount = OneCardEngine.TURN_LIMIT),
                        "turn limit reached but the match is still open"),
                broken("열린 판의 생존자 손패가 빔", draft().emptyHand(1), "live seat 1 has no cards"),
                broken("창이 탈락한 좌석을 예약함", reservedForEliminatedSeat(), "race reserves the eliminated seat 2"),

                broken("손패를 비운 생존자가 있는데 FINISHED 아님",
                        draft().emptyHand(0).endedAs(EndReason.STALEMATE, -1)
                                .also(d -> d.turnCount = OneCardEngine.TURN_LIMIT),
                        "has an empty hand but the match ended as STALEMATE"),
                broken("손패를 비운 생존자가 없는데 FINISHED", draft().endedAs(EndReason.FINISHED, 0),
                        "FINISHED but no live seat has an empty hand"),
                broken("LAST_STANDING 인데 생존자 2명", draft().endedAs(EndReason.LAST_STANDING, -1),
                        "LAST_STANDING needs exactly one live seat: 2"),
                broken("NO_HUMANS 인데 생존자 1명", draft().eliminate(1).endedAs(EndReason.NO_HUMANS, -1),
                        "NO_HUMANS needs at least two live seats: 1"),
                broken("STALEMATE 인데 생존자 1명", draft().eliminate(1).endedAs(EndReason.STALEMATE, -1)
                        .also(d -> d.turnCount = OneCardEngine.TURN_LIMIT),
                        "STALEMATE needs at least two live seats: 1"),
                broken("STALEMATE 인데 패스도 상한도 아님", draft().endedAs(EndReason.STALEMATE, -1),
                        "STALEMATE without a full pass streak or the turn limit"));
    }

    /** 3인 — 좌석 0 이 1장이 돼 창을 열었는데 다음 차례로 예약된 좌석 2 가 이미 탈락했다. */
    private static Draft reservedForEliminatedSeat() {
        Draft d = new Draft(table(3)).keep(0, 1).eliminate(2);
        d.turnSeat = -1;
        d.race = new RaceWindow(2, 0, 0, 0, 0, 0, 3_000, 2, null);
        return d;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenTables")
    void a_table_breaking_one_rule_is_caught_by_that_rule(String what, OneCardState broken, String message) {
        assertThatThrownBy(() -> OneCardInvariantChecker.check(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }
}
