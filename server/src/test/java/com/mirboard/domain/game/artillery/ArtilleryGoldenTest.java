package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 결정성 (`docs/rules-artillery.md` §12): 같은 (시드, 인원, 액션 열)이면 상태·이벤트가 비트 단위로 같다. 고정 해시는 룰 수치가
 * 바뀌면 깨지는 것이 맞다 — 그때는 룰 정본과 함께 값을 갱신한다.
 */
class ArtilleryGoldenTest {

    private static final ArtilleryEngine ENGINE = new ArtilleryEngine();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 시드 20261109, 4인, 정해진 12발. */
    static String play() throws Exception {
        long now = 1_000_000L;
        ArtilleryState s = ENGINE.startMatch(20261109L, 4, List.of()).newState();
        List<ArtilleryEvent> all = new ArrayList<>();
        int[][] shots = {{0, 60, 55}, {10, 120, 60}, {-20, 45, 70}, {0, 135, 50}, {30, 70, 40}, {0, 100, 65},
                {-10, 80, 75}, {0, 30, 45}, {5, 150, 55}, {0, 90, 35}, {-30, 65, 80}, {0, 110, 50}};
        for (int i = 0; i < shots.length && !s.ended(); i++) {
            Shell shell = i % 4 == 1 && s.unit(s.turnSeat()).pumpkins() > 0 ? Shell.PUMPKIN : Shell.PEA;
            ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                    new ArtilleryAction.Fire(shots[i][0], shots[i][1], shots[i][2], shell), now);
            all.addAll(r.events());
            s = r.newState();
            if (s.phase() == Phase.RESOLVING) {
                now = s.resolveUntil();
                r = ENGINE.expire(s, now);
                all.addAll(r.events());
                s = r.newState();
            }
        }
        return JSON.writeValueAsString(s) + "\n" + JSON.writeValueAsString(all) + "\n"
                + Long.toHexString(ArtilleryEngine.terrainOf(s).hash());
    }

    @Test
    void same_inputs_same_bits() throws Exception {
        assertThat(play()).isEqualTo(play());
    }

    @Test
    void pinned_hash() throws Exception {
        byte[] bytes = play().getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        System.out.println("[artillery-golden] " + Long.toHexString(h));
        assertThat(Long.toHexString(h)).isEqualTo(GOLDEN);
    }

    @Test
    void state_and_events_survive_a_json_round_trip() throws Exception {
        long now = 1_000_000L;
        ArtilleryState s = ENGINE.startMatch(5L, 3, List.of(2)).newState();
        ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(), new ArtilleryAction.Fire(-15, 70, 60, Shell.PUMPKIN), now);
        ArtilleryState back = JSON.readValue(JSON.writeValueAsString(r.newState()), ArtilleryState.class);
        assertThat(back).isEqualTo(r.newState());
        for (ArtilleryEvent e : r.events()) {
            assertThat(JSON.readValue(JSON.writeValueAsString(e), ArtilleryEvent.class)).isEqualTo(e);
        }
    }

    static final String GOLDEN = "3ede3ce03655062a";
}
