package com.mirboard.domain.game.artillery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.artillery.action.ArtilleryAction;
import com.mirboard.domain.game.artillery.event.ArtilleryEvent;
import com.mirboard.domain.game.artillery.rules.Item;
import com.mirboard.domain.game.artillery.rules.Shell;
import com.mirboard.domain.game.artillery.rules.UnitKind;
import com.mirboard.domain.game.artillery.state.ArtilleryState;
import com.mirboard.domain.game.artillery.state.Phase;
import com.mirboard.domain.game.artillery.state.Unit;
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

    /** S4 (D-224) — 시드 20261110, 3인(도토리·민들레·새싹), 특수탄·아이템을 섞은 9발. */
    static String playUnits() throws Exception {
        long now = 1_000_000L;
        ArtilleryState s = ENGINE.startMatch(20261110L, 3, List.of(), now).newState();
        List<ArtilleryEvent> all = new ArrayList<>();
        UnitKind[] kinds = {UnitKind.ACORN, UnitKind.DANDELION, UnitKind.SPROUT};
        for (int seat = 0; seat < 3; seat++) {
            ArtilleryEngine.Result r = ENGINE.apply(s, seat, new ArtilleryAction.Pick(kinds[seat]), now);
            all.addAll(r.events());
            s = r.newState();
        }
        int[][] shots = {{0, 60, 55}, {10, 120, 60}, {-20, 45, 70}, {0, 135, 50}, {30, 70, 40}, {0, 100, 65},
                {-10, 80, 75}, {0, 30, 45}, {5, 150, 55}};
        Item[] items = {null, Item.TWIN_SEED, null, Item.PINWHEEL, null, Item.WATERING_CAN, null, null, null};
        for (int i = 0; i < shots.length && !s.ended(); i++) {
            Unit me = s.unit(s.turnSeat());
            Shell shell = i % 3 == 0 && me.specials() > 0 ? me.kind().special() : Shell.PEA;
            Item item = items[i] != null && me.has(items[i]) ? items[i] : null;
            ArtilleryEngine.Result r = ENGINE.apply(s, s.turnSeat(),
                    new ArtilleryAction.Fire(shots[i][0], shots[i][1], shots[i][2], shell, item), now);
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

    private static String fnv(String text) {
        long h = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    @Test
    void same_inputs_same_bits() throws Exception {
        assertThat(play()).isEqualTo(play());
        assertThat(playUnits()).isEqualTo(playUnits());
    }

    @Test
    void pinned_hash_with_units_and_items() throws Exception {
        String h = fnv(playUnits());
        System.out.println("[artillery-golden-s4] " + h);
        assertThat(h).isEqualTo(GOLDEN_S4);
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

    /**
     * S4(D-224)에서 JSON 모양(유닛 종류·특수탄·아이템·대본 단계)이 바뀌어 다시 고정했다 — 물리는 그대로다(S3 판의 구멍·포대 위치·지형 해시
     * {@code 5ca69b141e21e61} 가 같음을 확인).
     */
    static final String GOLDEN = "1d50ab49c9f0c45b";
    static final String GOLDEN_S4 = "17e6582289b5958f";
}
