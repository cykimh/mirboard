package com.mirboard.domain.game.skullking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import com.mirboard.domain.game.skullking.state.TrickState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * S5(D-102) — Redis 영속 JSON 왕복 가드. 상태가 Jackson 을 통과하지 못하면 봇 매치가
 * 첫 액션에서 죽는다 (IT 에서 실제 발생했던 회귀).
 */
class SkullKingJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void a_suit_card_round_trips() throws Exception {
        SkullCard card = SkullCard.of(SkullSuit.GREEN, 5);
        String json = mapper.writeValueAsString(card);

        assertThat(mapper.readValue(json, SkullCard.class)).isEqualTo(card);
    }

    @Test
    void a_special_card_round_trips() throws Exception {
        SkullCard card = SkullCard.pirate();
        String json = mapper.writeValueAsString(card);

        assertThat(mapper.readValue(json, SkullCard.class)).isEqualTo(card);
    }

    @Test
    void a_bidding_state_round_trips() throws Exception {
        SkullKingState state = new SkullKingState.Bidding(3, List.of(
                PlayerState.initial(0, List.of(
                        SkullCard.of(SkullSuit.GREEN, 5), SkullCard.skullKing(),
                        SkullCard.of(SkullSuit.BLACK, 14))),
                PlayerState.initial(1, List.of(
                        SkullCard.escape(), SkullCard.tigress(),
                        SkullCard.of(SkullSuit.PURPLE, 1))).withBid(2)), 1);

        String json = mapper.writeValueAsString(state);

        assertThat(mapper.readValue(json, SkullKingState.class)).isEqualTo(state);
    }

    @Test
    void a_playing_state_with_a_tigress_in_the_trick_round_trips() throws Exception {
        TrickState trick = TrickState.lead(0)
                .with(PlayedCard.tigress(0, TigressMode.ESCAPE))
                .with(PlayedCard.of(1, SkullCard.of(SkullSuit.YELLOW, 9)));
        SkullKingState state = new SkullKingState.Playing(2, List.of(
                PlayerState.initial(0, List.of(SkullCard.of(SkullSuit.GREEN, 2))).withBid(0),
                PlayerState.initial(1, List.of(SkullCard.mermaid())).withBid(1)), 0, trick);

        String json = mapper.writeValueAsString(state);

        assertThat(mapper.readValue(json, SkullKingState.class)).isEqualTo(state);
    }

    @Test
    void a_round_end_state_with_won_tricks_round_trips() throws Exception {
        PlayedCard a = PlayedCard.of(0, SkullCard.of(SkullSuit.GREEN, 5));
        PlayedCard b = PlayedCard.of(1, SkullCard.of(SkullSuit.GREEN, 9));
        TrickResult trick = new TrickResult(1, b, List.of(a, b));
        SkullKingState state = new SkullKingState.RoundEnd(1, List.of(
                PlayerState.initial(0, List.of()).withBid(0),
                new PlayerState(1, List.of(), 1, List.of(trick))), 0,
                Map.of(0, new RoundScore(0, 0, 10, 0), 1, new RoundScore(1, 1, 20, 0)));

        String json = mapper.writeValueAsString(state);

        assertThat(mapper.readValue(json, SkullKingState.class)).isEqualTo(state);
    }

    @Test
    void a_match_state_with_desertions_round_trips() throws Exception {
        SkullKingMatchState match = new SkullKingMatchState(4, 2,
                Map.of(0, 40, 1, -20, 2, 90), Set.of(1));

        String json = mapper.writeValueAsString(match);

        assertThat(mapper.readValue(json, SkullKingMatchState.class)).isEqualTo(match);
    }

    /** 구 JSON(필드 부재) 호환 — desertedSeats 없는 매치 상태를 읽을 수 있어야 한다. */
    @Test
    void old_match_json_without_deserted_seats_still_reads() throws Exception {
        String old = "{\"roundNumber\":2,\"startSeat\":1,\"cumulativeScores\":{\"0\":10,\"1\":0}}";

        SkullKingMatchState match = mapper.readValue(old, SkullKingMatchState.class);

        assertThat(match.desertedSeats()).isEmpty();
        assertThat(match.roundNumber()).isEqualTo(2);
    }

    // ---------- D-120 — 라운드 기록 ----------

    @Test
    void match_state_with_completed_rounds_round_trips() throws Exception {
        SkullKingMatchState match = new SkullKingMatchState(3, 1,
                Map.of(0, 30, 1, -10), Set.of(),
                List.of(new SkullKingMatchState.CompletedRound(1, Map.of(
                                0, new RoundScore(1, 1, 20, 10), 1, new RoundScore(0, 1, -10, 0))),
                        new SkullKingMatchState.CompletedRound(2, Map.of(
                                0, new RoundScore(0, 0, 20, 0), 1, new RoundScore(2, 2, 40, 0)))));

        String json = mapper.writeValueAsString(match);

        assertThat(mapper.readValue(json, SkullKingMatchState.class)).isEqualTo(match);
        // 좌석 점수는 4필드만 영속된다 — total 은 파생값이라 JSON 에 싣지 않는다.
        assertThat(json).contains("\"completedRounds\"").doesNotContain("\"total\"");
    }

    /** 구 JSON(배포 시점에 진행 중이던 매치) — completedRounds 부재 → 빈 목록. */
    @Test
    void old_json_without_completed_rounds_reads() throws Exception {
        String withDeserted = "{\"roundNumber\":4,\"startSeat\":0,"
                + "\"cumulativeScores\":{\"0\":10,\"1\":0},\"desertedSeats\":[1]}";
        String withoutDeserted = "{\"roundNumber\":2,\"startSeat\":1,"
                + "\"cumulativeScores\":{\"0\":10,\"1\":0}}";

        SkullKingMatchState a = mapper.readValue(withDeserted, SkullKingMatchState.class);
        SkullKingMatchState b = mapper.readValue(withoutDeserted, SkullKingMatchState.class);

        assertThat(a.completedRounds()).isEmpty();
        assertThat(a.desertedSeats()).containsExactly(1);
        assertThat(b.completedRounds()).isEmpty();
        assertThat(b.roundNumber()).isEqualTo(2);
    }

    // ---------- D-122 — 완주 라운드 수 ----------

    @Test
    void ended_match_round_trips_its_rounds_played() throws Exception {
        SkullKingMatchState ended = SkullKingMatchState.initial(2, 0).abandoned();

        String json = mapper.writeValueAsString(ended);

        assertThat(json).contains("\"roundsPlayed\":0");
        assertThat(mapper.readValue(json, SkullKingMatchState.class)).isEqualTo(ended);
    }

    /** 구 JSON(D-122 이전) — 필드 부재는 null(미확정)로 읽혀 뷰가 예전 역산으로 떨어진다. */
    @Test
    void old_json_without_rounds_played_reads_as_unset() throws Exception {
        String old = "{\"roundNumber\":11,\"startSeat\":0,"
                + "\"cumulativeScores\":{\"0\":10,\"1\":0},\"desertedSeats\":[1],"
                + "\"completedRounds\":[]}";

        SkullKingMatchState match = mapper.readValue(old, SkullKingMatchState.class);

        assertThat(match.isMatchOver()).isTrue();
        assertThat(match.roundsPlayed()).isNull();
    }

    /**
     * 롤백 안전 — 다음에 필드가 늘어도 이 버전이 그 JSON 을 읽을 수 있어야 한다. 기본
     * {@code ObjectMapper} 는 모르는 필드에서 실패하므로 어노테이션이 실제로 일한다.
     */
    @Test
    void unknown_field_is_ignored() throws Exception {
        String future = "{\"roundNumber\":2,\"startSeat\":0,\"cumulativeScores\":{\"0\":5},"
                + "\"futureField\":true,"
                + "\"completedRounds\":[{\"roundNumber\":1,\"futureNested\":1,"
                + "\"scores\":{\"0\":{\"bid\":0,\"won\":0,\"base\":5,\"bonus\":0}}}]}";

        SkullKingMatchState match = mapper.readValue(future, SkullKingMatchState.class);

        assertThat(match.completedRounds()).containsExactly(
                new SkullKingMatchState.CompletedRound(1, Map.of(0, new RoundScore(0, 0, 5, 0))));
    }
}
