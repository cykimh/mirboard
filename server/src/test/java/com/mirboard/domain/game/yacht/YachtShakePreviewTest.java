package com.mirboard.domain.game.yacht;

import static com.mirboard.domain.game.yacht.YachtFixtures.T0;
import static com.mirboard.domain.game.yacht.YachtFixtures.engine;
import static com.mirboard.domain.game.yacht.YachtFixtures.openState;
import static com.mirboard.domain.game.yacht.YachtFixtures.simultaneousEngine;
import static com.mirboard.domain.game.yacht.YachtFixtures.withDeserted;
import static com.mirboard.domain.game.yacht.YachtFixtures.withTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.yacht.action.YachtAction;
import com.mirboard.domain.game.yacht.action.YachtActionRejectedException;
import com.mirboard.domain.game.yacht.dice.Dice;
import com.mirboard.domain.game.yacht.dice.DiceRoller;
import com.mirboard.domain.game.yacht.dice.HoldMask;
import com.mirboard.domain.game.yacht.event.YachtEvent;
import com.mirboard.domain.game.yacht.score.Category;
import com.mirboard.domain.game.yacht.state.YachtState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-268 — 컵 흔들기 미리보기(포트 ⑧, 순수 엔진). 굴릴 수 있는 좌석만 흔들 수 있고, 돌려주는 이벤트는 공개·순번 없음이며 상태를 바꾸지
 * 않는다. 흔들기 입력은 액션이 아니다 — 액션 경로에서는 거절되고 합법 수에 나오지 않는다.
 */
class YachtShakePreviewTest {

    private static final YachtAction.Shake START = new YachtAction.Shake(true, 7);
    private static final YachtAction.Shake STOP = new YachtAction.Shake(false, 8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DiceRoller ones = () -> 1;

    @Test
    void the_turn_owner_gets_a_public_unsequenced_shaking_event_and_the_state_is_untouched() {
        YachtEngine engine = engine(2);
        YachtState s = openState(2, 1, 0);
        YachtEvent.DiceShaking e = engine.preview(s, 0, START).orElseThrow();
        assertThat(e).isEqualTo(new YachtEvent.DiceShaking(0, true, 7));
        assertThat(e.sequenced()).isFalse();
        assertThat(e.privateSeat()).isEqualTo(-1);
        assertThat(e.envelopeType()).isEqualTo("DICE_SHAKING");
        assertThat(engine.preview(s, 0, STOP)).contains(new YachtEvent.DiceShaking(0, false, 8));
        // 순수 — 같은 상태 그대로(레코드 동등성).
        assertThat(s).isEqualTo(openState(2, 1, 0));
    }

    @Test
    void only_a_seat_that_can_roll_right_now_may_start_shaking() {
        YachtEngine engine = engine(2);
        YachtState s = openState(2, 1, 0);
        assertThat(engine.preview(s, 1, START)).as("남의 차례").isEmpty();
        assertThat(engine.preview(s, -1, START)).isEmpty();
        assertThat(engine.preview(s, 2, START)).isEmpty();
        assertThat(engine.preview(s, 0, null)).isEmpty();
        Dice d = Dice.of(1, 2, 3, 4, 5);
        assertThat(engine.preview(withTurn(s, d, HoldMask.NONE, 2), 0, START)).isPresent();
        assertThat(engine.preview(withTurn(s, d, HoldMask.NONE, 3), 0, START)).as("세 번 다 굴림").isEmpty();
        assertThat(engine.preview(withTurn(s, d, HoldMask.ALL, 1), 0, START)).as("다섯 개 모두 고정").isEmpty();
        assertThat(engine.preview(withDeserted(s, 0), 0, START)).as("탈주한 좌석").isEmpty();
    }

    /** 굴린 직후에 닿는 멈춤 프레임이 버려지면 남의 화면에 컵이 남는다 — 멈춤은 살아 있는 좌석이면 받는다. */
    @Test
    void a_stop_is_accepted_from_any_live_seat_but_not_from_a_deserter_or_a_finished_match() {
        YachtEngine engine = engine(2);
        YachtState s = openState(2, 1, 0);
        assertThat(engine.preview(withTurn(s, Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 3), 0, STOP))
                .contains(new YachtEvent.DiceShaking(0, false, 8));
        assertThat(engine.preview(s, 1, STOP)).contains(new YachtEvent.DiceShaking(1, false, 8));
        assertThat(engine.preview(withDeserted(s, 1), 1, STOP)).isEmpty();
        YachtEngine solo = engine(1);
        YachtState ended = solo.start(T0).newState();
        for (Category c : Category.values()) {
            ended = solo.apply(ended, 0, YachtAction.Roll.plain(), ones, T0).newState();
            ended = solo.apply(ended, 0, new YachtAction.Score(c), ones, T0).newState();
        }
        assertThat(ended.ended()).isTrue();
        assertThat(solo.preview(ended, 0, START)).isEmpty();
        assertThat(solo.preview(ended, 0, STOP)).isEmpty();
    }

    @Test
    void in_simultaneous_mode_each_pending_seat_shakes_its_own_board() {
        YachtEngine engine = simultaneousEngine(3, 0);
        YachtState s = engine.start(T0).newState();
        assertThat(engine.preview(s, 0, START)).contains(new YachtEvent.DiceShaking(0, true, 7));
        assertThat(engine.preview(s, 2, START)).contains(new YachtEvent.DiceShaking(2, true, 7));
        // 이번 라운드를 기록한 좌석은 더 굴릴 것이 없다.
        s = engine.apply(s, 1, YachtAction.Roll.plain(), ones, T0 + 1).newState();
        s = engine.apply(s, 1, new YachtAction.Score(Category.ONES), ones, T0 + 2).newState();
        assertThat(engine.preview(s, 1, START)).isEmpty();
        assertThat(engine.preview(s, 0, START)).isPresent();
    }

    /** 번호는 그대로 실어 보내고(서버는 기억하지 않는다 — 낡은 번호도 통과), 범위 밖·빠진 번호(0)는 버린다. */
    @Test
    void the_frame_number_is_echoed_untouched_and_only_range_checked() {
        YachtEngine engine = engine(2);
        YachtState s = openState(2, 1, 0);
        long big = YachtAction.Shake.MAX_N;
        assertThat(engine.preview(s, 0, new YachtAction.Shake(true, big))).contains(new YachtEvent.DiceShaking(0, true, big));
        assertThat(engine.preview(s, 0, new YachtAction.Shake(true, 1))).as("상태 없음 — 더 작은 번호도 그대로")
                .contains(new YachtEvent.DiceShaking(0, true, 1));
        assertThat(engine.preview(s, 0, new YachtAction.Shake(true, 0))).isEmpty();
        assertThat(engine.preview(s, 0, new YachtAction.Shake(false, -3))).isEmpty();
        assertThat(engine.preview(s, 0, new YachtAction.Shake(true, big + 1))).isEmpty();
        GameAction missing = JSON.convertValue(Map.of("@action", "SHAKE", "shaking", true), YachtAction.class);
        assertThat(engine.preview(s, 0, (YachtAction.Shake) missing)).isEmpty();
    }

    @Test
    void shake_is_not_an_action_and_is_never_a_legal_action() {
        YachtEngine engine = engine(2);
        YachtState s = openState(2, 1, 0);
        assertThatThrownBy(() -> engine.apply(s, 0, START, ones, T0))
                .isInstanceOf(YachtActionRejectedException.class)
                .hasMessageContaining("INVALID_ACTION");
        assertThat(engine.legalActions(s, 0)).noneMatch(YachtAction.Shake.class::isInstance);
        assertThat(engine.legalActions(withTurn(s, Dice.of(1, 2, 3, 4, 5), HoldMask.NONE, 1), 0))
                .noneMatch(YachtAction.Shake.class::isInstance);
    }

    @Test
    void the_wire_shapes_are_SHAKE_in_and_seat_plus_flag_out() throws Exception {
        GameAction input = JSON.convertValue(Map.of("@action", "SHAKE", "shaking", true, "n", 7), YachtAction.class);
        assertThat(input).isEqualTo(START);
        String json = JSON.writeValueAsString(new YachtEvent.DiceShaking(1, true, 1_794_533_649_971L));
        assertThat(json).contains("\"seat\":1").contains("\"shaking\":true").contains("\"n\":1794533649971")
                .doesNotContain("private")
                .doesNotContain("sequenced");
    }
}
