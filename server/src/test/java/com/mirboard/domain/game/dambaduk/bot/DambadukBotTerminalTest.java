package com.mirboard.domain.game.dambaduk.bot;

import static com.mirboard.domain.game.dambaduk.DambadukBoards.c;
import static com.mirboard.domain.game.dambaduk.DambadukBoards.playing;
import static org.assertj.core.api.Assertions.assertThat;

import com.mirboard.domain.game.dambaduk.DambadukEngine;
import com.mirboard.domain.game.dambaduk.DambadukStates;
import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.EndReason;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/**
 * S2 리뷰 지적 회귀 — 봇이 끝난 판을 엔진과 같게 매기는가. ① 점수가 같으면 가장 큰 집으로 가른다(엔진 순위), ② 굳음 끝을 끝으로 알아본다,
 * ③ 어려움은 끝난 판이 되는 수를 값이 아니라 판정으로 걸러 응수를 읽지 않는다.
 */
class DambadukBotTerminalTest {

    private static final DambadukEngine ENGINE = new DambadukEngine();
    private static final int HALF_WIN = 50_000;

    /**
     * 20:20 — 흑 집 12·8, 백 집 15·5, 빈 터 9. A5/A6 만 열려 있어 가운데가 다툼 터다. 흑이 B6 꺾은돌 → A6, 위 담으로 막으면 20:20·
     * 가장 큰 집 12:15 로 지고, 백이 A3 곧은돌 → A5, 아래 담으로 막으면 같은 점수에 가장 큰 집 15:12 로 이긴다.
     */
    private static DambadukState tieOnScore(int turn) {
        return playing(2, turn,
                "+---+---+---+---+---+---+---+",
                "| a   .   .   .   .   A | . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   . | . |",
                "+---+---+---+---+---+---+---+",
                "| B   .   .   .   b | .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   . | .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   . | .   . |",
                "+   +---+---+---+---+---+---+",
                "| .   a   .   A | b   .   B |",
                "+   +   +   +   +   +   +---+",
                "| .   .   .   . | .   . | . |",
                "+---+---+---+---+---+---+---+");
    }

    /**
     * 흑 21(위 3줄) 대 백 24(아래 3줄 + A4~C4). D4(흑)·E4(백)는 갇혔고 G4 흑 곧은돌만 움직인다 — G4 → F4 는 담을 어디에 세우든 굳음으로 끝나
     * 흑이 진다(23:24 또는 21:24). 위 꺾은돌을 옮기면 판은 계속된다.
     */
    private static DambadukState losingFreeze() {
        return playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| a   .   .   .   .   .   a |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   . |",
                "+---+---+---+---+---+---+---+",
                "| .   .   . | A   B   .   A |",
                "+   +   +   +---+---+---+---+",
                "| .   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| b   .   B   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   b |",
                "+---+---+---+---+---+---+---+");
    }

    private static BotBoard after(DambadukState s, int stoneAtX, int stoneAtY, Dir wall, Dir... path) {
        int id = s.stoneAt(new com.mirboard.domain.game.dambaduk.board.Cell(stoneAtX, stoneAtY)).orElseThrow().id();
        DambadukState n = ENGINE.apply(s, s.turnSeat(), new DambadukAction.Move(id, List.of(path), wall)).newState();
        return BotBoard.of(DambadukBotView.of(n, 0));
    }

    private static DambadukState play(DambadukState s, DambadukBotLevel level) {
        List<DambadukAction> legal = ENGINE.legalActions(s, s.turnSeat());
        DambadukAction a = DambadukBotPolicy.choose(DambadukBotView.of(s, s.turnSeat()), legal, level);
        return ENGINE.apply(s, s.turnSeat(), a).newState();
    }

    @Test
    void a_separation_tied_on_score_is_decided_by_the_largest_home_like_the_engine() {
        BotBoard closed = after(tieOnScore(0), 1, 5, Dir.UP, Dir.LEFT);           // 흑 B6 → A6, 위 담
        DambadukBotPolicy.Assessment black = DambadukBotPolicy.assess(closed, 0, true);
        DambadukBotPolicy.Assessment white = DambadukBotPolicy.assess(closed, 1, true);
        assertThat(black.terminal()).isTrue();
        assertThat(black.value()).as("20:20, 가장 큰 집 12:15 — 흑 패").isLessThan(-HALF_WIN);
        assertThat(white.value()).as("백 승").isGreaterThan(HALF_WIN);
    }

    @ParameterizedTest
    @EnumSource(value = DambadukBotLevel.class, names = {"NORMAL", "HARD"})
    void the_losing_tie_on_score_closure_is_avoided_and_the_winning_one_is_taken(DambadukBotLevel level) {
        DambadukState black = play(tieOnScore(0), level);
        assertThat(black.isOver() && !black.result().winners().contains(0)).as("흑이 지는 마무리를 두지 않는다").isFalse();
        DambadukState white = play(tieOnScore(1), level);
        assertThat(white.isOver()).as("백은 가장 큰 집으로 이기는 마무리를 둔다").isTrue();
        assertThat(white.result().reason()).isEqualTo(EndReason.SEPARATED);
        assertThat(white.result().winners()).containsExactly(1);
    }

    @Test
    void a_frozen_board_is_recognised_as_the_end_and_scored_by_the_engine() {
        BotBoard frozen = after(losingFreeze(), 6, 3, Dir.LEFT, Dir.LEFT);        // 흑 G4 → F4, 왼쪽 담
        DambadukBotPolicy.Assessment black = DambadukBotPolicy.assess(frozen, 0, true);
        assertThat(black.terminal()).as("굳음은 끝이다").isTrue();
        assertThat(black.value()).as("23:24 — 흑 패").isLessThan(-HALF_WIN);
        assertThat(DambadukBotPolicy.assess(frozen, 1, true).value()).isGreaterThan(HALF_WIN);
    }

    @ParameterizedTest
    @EnumSource(value = DambadukBotLevel.class, names = {"NORMAL", "HARD"})
    void a_losing_freeze_is_avoided(DambadukBotLevel level) {
        DambadukState n = play(losingFreeze(), level);
        assertThat(n.isOver()).as("굳음으로 지는 수 대신 위 꺾은돌을 옮긴다").isFalse();
    }

    @Test
    void hard_does_not_read_replies_on_a_move_that_ends_the_match_in_a_draw() {
        // 엔진 테스트의 사다리 판 — 흑 C3 → D3, 아래 담이면 21:21·가장 큰 집 21:21 무승부로 끝난다(값 0).
        DambadukState s = playing(2, 0,
                "+---+---+---+---+---+---+---+",
                "| a   .   .   .   .   .   a |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   A   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   A   .   .   .   . |",
                "+---+---+---+   +---+---+---+",
                "| b   .   .   .   .   .   . |",
                "+   +   +   +   +   +   +   +",
                "| .   B   .   .   .   B   . |",
                "+   +   +   +   +   +   +   +",
                "| .   .   .   .   .   .   b |",
                "+---+---+---+---+---+---+---+",
                "| .   .   .   .   .   .   . |",
                "+---+---+---+---+---+---+---+");
        DambadukBotView view = DambadukBotView.of(s, 0);
        List<DambadukBotPolicy.Candidate> cands =
                DambadukBotPolicy.candidates(view, BotBoard.of(view), ENGINE.legalActions(s, 0));
        int c3 = s.stoneAt(c("C3")).orElseThrow().id();
        DambadukBotPolicy.Scored drawMove = DambadukBotPolicy.hardScores(view, cands).stream()
                .filter(x -> x.action().equals(new DambadukAction.Move(c3, List.of(Dir.RIGHT), Dir.DOWN)))
                .findFirst().orElseThrow();
        assertThat(drawMove.terminal()).isTrue();
        assertThat(drawMove.value()).as("무승부 — 판 위 응수로 부풀리지 않는다").isZero();
        assertThat(drawMove.nodes()).as("응수를 읽지 않았다").isZero();
    }

    @Test
    void placement_is_never_treated_as_an_ended_board() {
        // 3인 첫 돌 — 돌이 하나뿐이라 다툼 터가 없지만 배치 단계는 끝 판정이 없다.
        DambadukState s = DambadukStates.place(DambadukStates.three(), "D4");
        BotBoard b = BotBoard.of(DambadukBotView.of(s, 1));
        assertThat(DambadukBotPolicy.assess(b, 1, false).terminal()).isFalse();
    }
}
