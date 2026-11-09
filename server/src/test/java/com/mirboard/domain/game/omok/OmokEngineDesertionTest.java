package com.mirboard.domain.game.omok;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static com.mirboard.domain.game.omok.OmokStates.teams;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.event.OmokEvent;
import com.mirboard.domain.game.omok.state.EndReason;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 탈주 (룰 §9) — 그 편 패, 판 종료. */
class OmokEngineDesertionTest {

    @Test
    void solo_desertion_hands_the_win_to_the_other_player() {
        OmokEngine.Desertion d = ENGINE.desert(solo(OmokRule.RENJU), 0);
        assertThat(d.ended()).isTrue();
        assertThat(d.events()).containsExactly(new OmokEvent.MatchEnded(Stone.WHITE, List.of(1), EndReason.DESERTED, 0,
                List.of(), 0));
        assertThat(d.newState().turnSeat()).isEqualTo(-1);
    }

    @Test
    void team_desertion_loses_for_the_deserters_team() {
        OmokEngine.Desertion d = ENGINE.desert(teams(OmokRule.RENJU), 3);   // 백 팀 1·3
        assertThat(d.newState().result().winnerSeats()).containsExactly(0, 2);
        assertThat(d.newState().result().winnerColor()).isEqualTo(Stone.BLACK);
        assertThat(d.newState().result().causeSeat()).isEqualTo(3);
    }

    @Test
    void desertion_after_the_end_or_from_a_bad_seat_is_not_applicable() {
        OmokState over = ENGINE.apply(solo(OmokRule.RENJU), 0, new OmokAction.Resign()).newState();
        OmokEngine.Desertion late = ENGINE.desert(over, 1);
        assertThat(late.ended()).isFalse();
        assertThat(late.newState()).isEqualTo(over);
        assertThat(late.events()).isEmpty();
        assertThat(ENGINE.desert(solo(OmokRule.RENJU), 2).ended()).isFalse();
    }
}
