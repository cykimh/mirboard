package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.persistence.ArtilleryStateStore;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.core.GameAction;
import com.mirboard.domain.game.core.GameContext;
import com.mirboard.domain.game.core.GameEvent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * S6a 조준 미리보기 (D-229, `docs/plans/artillery-s6.md` §5.3) — 차례 주인·단계·각도만 통과, 쏠 수 없는 탄은 null, 힘·위치는 없음,
 * 순번 없음·공개. 상태를 바꾸지 않는다.
 */
class ArtilleryPreviewTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ArtilleryState aiming() {
        return ENGINE.startMatch(9L, 3, List.of(), List.of(UnitKind.ACORN, UnitKind.DANDELION, UnitKind.SPROUT),
                ArtilleryEngine.Setup.SOLO).newState();
    }

    @Test
    void the_turn_owner_gets_an_unsequenced_public_aim_preview() {
        ArtilleryState s = aiming();
        int seat = s.turnSeat();
        Shell mine = s.unit(seat).kind().special();
        ArtilleryEvent.AimPreview p = ENGINE.preview(s, seat, new ArtilleryAction.Preview(135, mine, 7)).orElseThrow();
        assertThat(p).isEqualTo(new ArtilleryEvent.AimPreview(seat, s.turnNo(), 135, mine, 7));
        assertThat(p.sequenced()).isFalse();
        assertThat(p.privateSeat()).isEqualTo(-1);
        assertThat(p.envelopeType()).isEqualTo("AIM_PREVIEW");
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(0, null, 1)).orElseThrow().shell()).isNull();
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(180, Shell.PEA, 2))).isPresent();
    }

    @Test
    void others_wrong_phases_and_out_of_range_angles_get_nothing() {
        ArtilleryState s = aiming();
        int seat = s.turnSeat();
        assertThat(ENGINE.preview(s, (seat + 1) % 3, new ArtilleryAction.Preview(45, Shell.PEA, 1))).isEmpty();
        assertThat(ENGINE.preview(s, -1, new ArtilleryAction.Preview(45, Shell.PEA, 1))).isEmpty();
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(-1, Shell.PEA, 1))).isEmpty();
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(181, Shell.PEA, 1))).isEmpty();
        assertThat(ENGINE.preview(s, seat, null)).isEmpty();
        ArtilleryState resolving = ENGINE.apply(s, seat, new ArtilleryAction.Fire(0, 90, 20, Shell.PEA), 1L).newState();
        assertThat(resolving.phase()).isEqualTo(Phase.RESOLVING);
        assertThat(ENGINE.preview(resolving, seat, new ArtilleryAction.Preview(45, Shell.PEA, 1))).isEmpty();
        ArtilleryState picking = ENGINE.startMatch(9L, 3, List.of(), 1L, ArtilleryEngine.Setup.SOLO).newState();
        assertThat(ENGINE.preview(picking, 0, new ArtilleryAction.Preview(45, Shell.PEA, 1))).isEmpty();
    }

    /** 없는 특수탄(남의 종류)·다 쓴 호박탄으로 허세를 부리지 못한다. */
    @Test
    void a_shell_the_unit_cannot_fire_is_dropped_to_null() {
        ArtilleryState s = aiming();
        int seat = s.turnSeat();
        Shell mine = s.unit(seat).kind().special();
        Shell foreign = java.util.Arrays.stream(Shell.values()).filter(sh -> sh.special() && sh != mine).findFirst().orElseThrow();
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(45, foreign, 1)).orElseThrow().shell()).isNull();
        ArtilleryState noPumpkins = ArtilleryFixtures.withUnit(s, s.unit(seat).withPumpkins(0));
        assertThat(ENGINE.preview(noPumpkins, seat, new ArtilleryAction.Preview(45, Shell.PUMPKIN, 1)).orElseThrow().shell())
                .isNull();
        assertThat(ENGINE.preview(s, seat, new ArtilleryAction.Preview(45, Shell.PUMPKIN, 1)).orElseThrow().shell())
                .isEqualTo(Shell.PUMPKIN);
    }

    /** 힘은 입력 타입에 없다 — 수정된 클라가 실어 보내도 역직렬화에서 버려지고 이벤트 JSON 에도 없다. 위치도 싣지 않는다. */
    @Test
    void power_is_neither_read_nor_sent() throws Exception {
        ArtilleryState s = aiming();
        int seat = s.turnSeat();
        GameAction input = JSON.convertValue(Map.of("@action", "PREVIEW", "angle", 60, "shell", "PEA", "n", 3,
                "power", 77), ArtilleryAction.class);
        assertThat(input).isEqualTo(new ArtilleryAction.Preview(60, Shell.PEA, 3));
        String json = JSON.writeValueAsString(ENGINE.preview(s, seat, (ArtilleryAction.Preview) input).orElseThrow());
        assertThat(json).doesNotContain("power").doesNotContain("\"x\"").doesNotContain("77")
                .contains("\"angle\":60").contains("\"n\":3");
    }

    @Test
    void the_adapter_passes_only_preview_inputs_and_never_touches_the_store() {
        ArtilleryStateStore store = mock(ArtilleryStateStore.class);
        ArtilleryGameEngine adapter = new ArtilleryGameEngine(new GameContext("r1", List.of(1L, 2L, 3L)), ENGINE, store,
                Clock.systemUTC(), e -> { });
        ArtilleryState s = aiming();
        int seat = s.turnSeat();
        Optional<GameEvent> ok = adapter.preview(s, seat, new ArtilleryAction.Preview(30, null, 1));
        assertThat(ok).containsInstanceOf(ArtilleryEvent.AimPreview.class);
        assertThat(adapter.preview(s, seat, new ArtilleryAction.Fire(0, 30, 50, Shell.PEA))).isEmpty();
        assertThat(adapter.preview(s, seat, new ArtilleryAction.Move(5))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(store);
    }
}
