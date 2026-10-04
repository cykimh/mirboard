package com.mirboard.domain.game.onecard;

import static com.mirboard.domain.game.onecard.OneCardTables.BLACK_JOKER;
import static com.mirboard.domain.game.onecard.OneCardTables.club;
import static com.mirboard.domain.game.onecard.OneCardTables.diamond;
import static com.mirboard.domain.game.onecard.OneCardTables.hand;
import static com.mirboard.domain.game.onecard.OneCardTables.heart;
import static com.mirboard.domain.game.onecard.OneCardTables.seats;
import static com.mirboard.domain.game.onecard.OneCardTables.spade;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.onecard.action.OneCardAction;
import com.mirboard.domain.game.onecard.card.PlayingCard;
import com.mirboard.domain.game.onecard.card.Suit;
import com.mirboard.domain.game.onecard.event.OneCardEvent;
import com.mirboard.domain.game.onecard.state.Elimination;
import com.mirboard.domain.game.onecard.state.MatchResult;
import com.mirboard.domain.game.onecard.state.OneCardState;
import com.mirboard.domain.game.onecard.state.RaceWindow;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * S3 가 상태를 Redis 에 JSON 으로 저장하고 액션을 클라 JSON 에서 읽는다. 판정 메서드({@code isJoker} 등)가
 * 프로퍼티로 새거나 컴포넌트를 지우는 회귀(D-102 의 스컬킹 사례)를 여기서 막는다.
 */
class OneCardJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    @Test
    void cards_round_trip() throws Exception {
        for (PlayingCard card : List.of(heart(5), BLACK_JOKER)) {
            assertThat(roundTrip(card, PlayingCard.class)).isEqualTo(card);
        }
    }

    @Test
    void a_state_with_a_race_a_declared_suit_and_an_elimination_round_trips() throws Exception {
        OneCardState state = seats(hand(club(3)), hand(), hand(diamond(4), diamond(6), spade(8)))
                .eliminated(1, Elimination.Reason.BANKRUPT, 20)
                .top(heart(7)).declared(Suit.SPADE).attack(0).passStreak(1)
                .race(new RaceWindow(4, 0, 3, -20, 40, 1_000, 3_000, 2, new RaceWindow.BotPress(2, false, 1_500)))
                .build();

        assertThat(roundTrip(state, OneCardState.class)).isEqualTo(state);
    }

    @Test
    void a_finished_state_round_trips() throws Exception {
        OneCardState open = seats(hand(), hand(spade(4), spade(6))).top(heart(2)).turn(-1).build();
        OneCardState finished = new OneCardState(open.hands(), open.drawPile(), open.discardPile(), -1, 1, null, 2,
                null, List.of(), 0, 12, 9, new MatchResult(MatchResult.EndReason.FINISHED, List.of(
                        new MatchResult.Standing(0, 1, 0, MatchResult.SeatStatus.FINISHED),
                        new MatchResult.Standing(1, 2, 2, MatchResult.SeatStatus.ALIVE))));

        assertThat(roundTrip(finished, OneCardState.class)).isEqualTo(finished);
    }

    @Test
    void every_action_round_trips_through_the_sealed_type() throws Exception {
        for (OneCardAction action : List.of(new OneCardAction.PlayCard(heart(7), Suit.SPADE),
                OneCardAction.PlayCard.of(BLACK_JOKER), new OneCardAction.Draw(),
                new OneCardAction.CallOneCard(4), new OneCardAction.Catch(4))) {
            assertThat(roundTrip(action, OneCardAction.class)).isEqualTo(action);
        }
        assertThat(mapper.readValue("{\"@action\":\"DRAW\"}", OneCardAction.class))
                .isEqualTo(new OneCardAction.Draw());
    }

    /** sealed 계층 11종 하나씩. 새 이벤트가 생기면 아래 첫 단언이 이 목록을 고치라고 실패한다. */
    private static List<OneCardEvent> oneOfEachEvent() {
        return List.of(
                new OneCardEvent.MatchStarted(2, heart(5), 7, 26),
                new OneCardEvent.HandDealt(1, List.of(heart(9), BLACK_JOKER), 1),
                new OneCardEvent.HandUpdated(3, List.of(club(3)), List.of(club(3)), 4),
                new OneCardEvent.CardPlayed(0, heart(7), Suit.SPADE, 2, 0, -1),
                new OneCardEvent.CardsDrawn(2, 1, OneCardEvent.DrawReason.PENALTY, 3, 20),
                new OneCardEvent.PileReshuffled(30),
                new OneCardEvent.TurnChanged(1, -1, 2),
                new OneCardEvent.RaceOpened(7, 0, 3, -20, 40, 3_000),
                new OneCardEvent.RaceResolved(7, OneCardEvent.RaceOutcome.CAUGHT, 2),
                new OneCardEvent.PlayerEliminated(1, Elimination.Reason.BANKRUPT, 20, 21),
                new OneCardEvent.MatchEnded(MatchResult.EndReason.LAST_STANDING, List.of(
                        new MatchResult.Standing(0, 1, 3, MatchResult.SeatStatus.ALIVE),
                        new MatchResult.Standing(1, 2, 20, MatchResult.SeatStatus.BANKRUPT))));
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void events_carry_their_envelope_type_and_the_race_event_no_bot_timing() throws Exception {
        List<OneCardEvent> events = oneOfEachEvent();
        assertThat(events.stream().<Class<?>>map(Object::getClass).toList())
                .containsExactlyInAnyOrder(OneCardEvent.class.getPermittedSubclasses());

        for (OneCardEvent event : events) {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

            assertThat(json.get("@event").asText()).as("%s", event).isEqualTo(event.envelopeType());
            // core GameEvent.isPrivate() 가 is-getter 로 새면 모든 payload 에 "private" 키가 붙는다.
            assertThat(json.has("private")).as("%s", event).isFalse();
            int privateSeat = switch (event) {
                case OneCardEvent.HandDealt dealt -> dealt.seat();
                case OneCardEvent.HandUpdated updated -> updated.seat();
                default -> -1;
            };
            assertThat(event.privateSeat()).as("%s", event).isEqualTo(privateSeat);
            assertThat(event.isPrivate()).as("%s", event).isEqualTo(privateSeat >= 0);

            switch (event) {
                // 봇의 반응 시각(botPress·delay)은 어떤 키로도 나가지 않는다 — 키 집합이 정확히 이것뿐이다.
                case OneCardEvent.RaceOpened __ -> assertThat(keysOf(json)).containsExactlyInAnyOrder(
                        "@event", "raceId", "ownerSeat", "slot", "jitterX", "jitterY", "windowMillis");
                case OneCardEvent.CardPlayed played ->
                        assertThat(json.get("direction").asInt()).isEqualTo(played.direction());
                case OneCardEvent.PlayerEliminated out ->
                        assertThat(json.get("drawPileCount").asInt()).isEqualTo(out.drawPileCount());
                default -> {
                }
            }
        }
    }
}
