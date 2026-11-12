package com.mirboard.domain.game.omok.bot;

import static com.mirboard.domain.game.omok.OmokStates.ENGINE;
import static com.mirboard.domain.game.omok.OmokStates.p;
import static com.mirboard.domain.game.omok.OmokStates.play;
import static com.mirboard.domain.game.omok.OmokStates.solo;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.invariant.OmokInvariantChecker;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 봇 정책의 필수수·결정성·금수 회피 (D-179). */
class OmokBotPolicyTest {

    private static Point choose(OmokState s, BotLevel level) {
        List<OmokAction> legal = ENGINE.legalActions(s, s.turnSeat());
        OmokAction a = OmokBotPolicy.choose(OmokBotView.of(s), legal, level);
        assertThat(legal).contains(a);
        OmokAction.Place place = (OmokAction.Place) a;
        return p(place.x(), place.y());
    }

    @ParameterizedTest
    @EnumSource(BotLevel.class)
    void the_first_move_is_the_center(BotLevel level) {
        assertThat(choose(solo(OmokRule.RENJU), level)).isEqualTo(OmokEngine.CENTER);
    }

    @ParameterizedTest
    @EnumSource(BotLevel.class)
    void every_level_takes_a_five(BotLevel level) {
        // 흑 (5..8, 7) 넷, 흑 차례 — (4,7)·(9,7) 이 5.
        OmokState s = play(solo(OmokRule.RENJU), p(5, 7), p(0, 0), p(6, 7), p(0, 2), p(7, 7), p(0, 4), p(8, 7), p(0, 6));
        assertThat(choose(s, level)).isIn(p(4, 7), p(9, 7));
    }

    @ParameterizedTest
    @EnumSource(BotLevel.class)
    void every_level_blocks_a_five(BotLevel level) {
        // 백 (5..8, 9) 넷(왼쪽은 흑이 막음), 흑 차례 — (9,9) 를 막아야 한다.
        OmokState s = play(solo(OmokRule.RENJU), p(4, 9), p(5, 9), p(0, 0), p(6, 9), p(0, 2), p(7, 9), p(0, 4), p(8, 9));
        assertThat(choose(s, level)).isEqualTo(p(9, 9));
    }

    @ParameterizedTest
    @EnumSource(value = BotLevel.class, names = {"NORMAL", "HARD"})
    void normal_and_hard_stop_an_open_three(BotLevel level) {
        // 백 (6,9)(7,9)(8,9) 열린 3, 흑 차례 — 막지 않으면 열린 4 가 된다.
        OmokState s = play(solo(OmokRule.RENJU), p(0, 0), p(6, 9), p(0, 2), p(7, 9), p(0, 4), p(8, 9));
        Point move = choose(s, level);
        assertThat(move).isIn(p(5, 9), p(9, 9), p(4, 9), p(10, 9));
    }

    @ParameterizedTest
    @EnumSource(value = BotLevel.class, names = {"NORMAL", "HARD"})
    void normal_and_hard_make_an_open_four(BotLevel level) {
        // 흑 (6..8, 7) 열린 3, 상대 위협 없음 — 열린 4 를 만든다.
        OmokState s = play(solo(OmokRule.RENJU), p(6, 7), p(0, 0), p(7, 7), p(0, 2), p(8, 7), p(0, 14));
        assertThat(choose(s, level)).isIn(p(5, 7), p(9, 7));
    }

    @Test
    void hard_finds_a_four_four_win_by_vcf() {
        // 흑: 가로 (6,7)(7,7)(8,7) 막힌 셋(왼쪽 백) + 세로 (9,4)(9,5)(9,6) 막힌 셋(위 백) — 흑 렌주에선 (9,7) 이 44 금수라
        // 자유 오목으로 본다. (9,7) 하나로 4 둘 → 다음에 반드시 5.
        OmokState s = play(solo(OmokRule.FREESTYLE), p(6, 7), p(5, 7), p(7, 7), p(9, 3), p(8, 7), p(0, 0),
                p(9, 4), p(0, 2), p(9, 5), p(0, 4), p(9, 6), p(0, 14));
        assertThat(choose(s, BotLevel.HARD)).isEqualTo(p(9, 7));
    }

    @Test
    void black_never_picks_a_forbidden_point() {
        // (7,7) 이 33 금수인 국면에서 흑 봇 — 공격 점수가 가장 높은 자리라도 고르지 않는다(legal 이 이미 뺀다).
        OmokState s = play(solo(OmokRule.RENJU), p(7, 5), p(0, 0), p(7, 6), p(0, 2), p(5, 7), p(0, 4), p(6, 7), p(0, 6));
        assertThat(ForbiddenRule.check(s.board(), p(7, 7), OmokRule.RENJU)).isPresent();
        for (BotLevel level : BotLevel.values()) {
            assertThat(choose(s, level)).as(level.name()).isNotEqualTo(p(7, 7));
        }
    }

    @ParameterizedTest
    @EnumSource(BotLevel.class)
    void the_same_position_gives_the_same_move(BotLevel level) {
        OmokState s = play(solo(OmokRule.RENJU), p(7, 7), p(8, 8), p(6, 8), p(8, 6), p(7, 9));
        assertThat(choose(s, level)).isEqualTo(choose(s, level));
    }

    @Test
    void bots_finish_every_mode_and_rule_with_legal_moves_only() {
        // 2:2 는 좌석마다 다른 난이도 — 팀원 돌은 그냥 같은 색 돌로 본다(설계서 §4.4).
        for (OmokMode mode : OmokMode.values()) {
            for (OmokRule rule : OmokRule.values()) {
                for (int g = 0; g < 2; g++) {
                    OmokState s = ENGINE.startMatch(mode, rule, mode.seatCount(), g % 2, g % 2);
                    while (!s.isOver()) {
                        int seat = s.turnSeat();
                        BotLevel level = BotLevel.values()[(seat + g) % 3];
                        List<OmokAction> legal = ENGINE.legalActions(s, seat);
                        OmokAction a = OmokBotPolicy.choose(OmokBotView.of(s), legal, level);
                        assertThat(legal).as(mode + " " + rule).contains(a);
                        s = ENGINE.apply(s, seat, a).newState();
                    }
                    OmokInvariantChecker.check(s);
                }
            }
        }
    }

    @Test
    void empty_legal_list_gives_null() {
        assertThat(OmokBotPolicy.choose(OmokBotView.of(solo(OmokRule.RENJU)), List.of(), BotLevel.NORMAL)).isNull();
    }

    @Test
    void level_parsing_defaults_to_normal() {
        assertThat(BotLevel.parse("HARD")).isEqualTo(BotLevel.HARD);
        assertThat(BotLevel.parse("bogus")).isEqualTo(BotLevel.NORMAL);
        assertThat(BotLevel.parse(null)).isEqualTo(BotLevel.NORMAL);
    }
}
