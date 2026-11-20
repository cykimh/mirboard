package com.mirboard.domain.game.dambaduk.bot;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.DambadukStates;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 봇 정책 (D-277) — 늘 합법 수, 결정적, 이기는 마무리는 두고 지는 마무리는 피한다. */
class DambadukBotPolicyTest {

    private static final DambadukEngine ENGINE = new DambadukEngine();

    /** 룰 §12.3 그림에서 C4|D4 만 열린 판 — 흑이 C5 → C4 로 막으면 21:16 으로 이기고, 백이 E4 → D4 로 막으면 진다. */
    private static DambadukState almostSeparated(int turn) {
        return playing(2, turn,
                "+---+---+---+---+---+---+---+",
                "| .   a   . | .   .   b   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | B   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| A   .   . | .   .   .   B |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   b   .   . |",
                "+   +   +   +---+---+---+---+",
                "| .   .   A | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   a   . | .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   . | .   .   .   . |",
                "+---+---+---+---+---+---+---+");
    }

    private static DambadukAction choose(DambadukState s, DambadukBotLevel level) {
        List<DambadukAction> legal = ENGINE.legalActions(s, s.turnSeat());
        DambadukAction a = DambadukBotPolicy.choose(DambadukBotView.of(s, s.turnSeat()), legal, level);
        assertThat(legal).contains(a);
        return a;
    }

    @ParameterizedTest
    @EnumSource(DambadukBotLevel.class)
    void every_level_plays_legal_and_deterministic_moves_in_every_phase(DambadukBotLevel level) {
        for (DambadukState s : List.of(DambadukStates.two(), DambadukStates.three(), DambadukStates.twoPlaying(),
                DambadukStates.threePlaying(), almostSeparated(0))) {
            assertThat(choose(s, level)).isEqualTo(choose(s, level));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DambadukBotLevel.class, names = {"NORMAL", "HARD"})
    void a_winning_closure_is_taken(DambadukBotLevel level) {
        DambadukState s = almostSeparated(0);
        DambadukState n = ENGINE.apply(s, 0, choose(s, level)).newState();
        assertThat(n.isOver()).isTrue();
        assertThat(n.result().reason()).isEqualTo(EndReason.SEPARATED);
        assertThat(n.result().winners()).containsExactly(0);
    }

    @ParameterizedTest
    @EnumSource(value = DambadukBotLevel.class, names = {"NORMAL", "HARD"})
    void a_losing_closure_is_avoided(DambadukBotLevel level) {
        DambadukState s = almostSeparated(1);
        DambadukState n = ENGINE.apply(s, 1, choose(s, level)).newState();
        assertThat(n.isOver() && !n.result().winners().contains(1)).isFalse();
    }
}
