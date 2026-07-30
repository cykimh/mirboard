package com.mirboard.domain.game.skullking.bid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 예측 범위 (§5, §13-⑪). */
class BidRulesTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 8, 10})
    void max_bid_equals_hand_size(int handSize) {
        assertThat(BidRules.maxBid(handSize)).isEqualTo(handSize);
    }

    @Test
    void zero_is_a_valid_bid() {
        assertThat(BidRules.isValid(0, 5)).isTrue();
    }

    @Test
    void bids_outside_the_range_are_invalid() {
        assertThat(BidRules.isValid(-1, 5)).isFalse();
        assertThat(BidRules.isValid(6, 5)).isFalse();
        assertThat(BidRules.isValid(Integer.MAX_VALUE, 5)).isFalse();
    }

    @Test
    void legal_bids_span_zero_through_hand_size() {
        assertThat(BidRules.legalBids(3)).containsExactly(0, 1, 2, 3);
        assertThat(BidRules.legalBids(1)).containsExactly(0, 1);
    }

    /** 라운드 1 은 손패가 1장이라 0 또는 1 만 가능하다. */
    @Test
    void round_one_allows_only_zero_or_one() {
        assertThat(BidRules.legalBids(1)).hasSize(2);
    }

    /**
     * 명세 함정 #3 — 8인 라운드 9·10 은 손패가 8장이라 상한이 8 이다. 라운드 번호로
     * 잡으면 달성 불가능한 9·10 예측이 통과한다.
     */
    @Test
    void eight_player_late_rounds_cap_at_eight() {
        assertThat(BidRules.legalBids(8)).hasSize(9).endsWith(8);
        assertThat(BidRules.isValid(9, 8)).isFalse();
        assertThat(BidRules.isValid(10, 8)).isFalse();
    }
}
