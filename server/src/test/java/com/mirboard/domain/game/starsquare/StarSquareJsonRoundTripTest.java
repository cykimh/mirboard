package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.engine;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.playing;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.starsquare.action.StarSquareAction;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 상태는 Redis 에 JSON 으로 저장되고(S2) 액션은 클라 JSON 에서 읽힌다. 그리고 <b>정답·푸는 중인 남의 판이 공개 뷰·이벤트 JSON 에 없다</b>
 * (`docs/rules-starsquare.md` §11 — 직렬화 타입 분리로 은닉).
 */
class StarSquareJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StarSquareState midGame() {
        StarSquareEngine e = engine(3, Set.of(2), 3, 300);
        StarSquareState s = playing(e, loShuPuzzles(3));
        s = e.apply(s, 0, sync(0, 4, 2, 7, 6, 0, 0, 1, 0, 0, 0), s.phaseStartedAt() + 500).newState();
        s = e.onTimer(s, s.phaseStartedAt() + s.board(2).pace().milestoneAt(2)).newState();
        return s;
    }

    @Test
    void the_action_reads_the_documented_json() throws Exception {
        StarSquareAction a = mapper.readValue(
                "{\"@action\":\"SYNC\",\"puzzle\":0,\"version\":12,\"grid\":[8,1,6,0,5,0,4,0,2]}",
                StarSquareAction.class);
        assertThat(a).isEqualTo(sync(0, 12, 8, 1, 6, 0, 5, 0, 4, 0, 2));
        assertThat(mapper.writeValueAsString(a)).contains("\"@action\":\"SYNC\"");
    }

    @Test
    void grids_are_plain_arrays_and_states_round_trip() throws Exception {
        assertThat(mapper.writeValueAsString(LO_SHU)).isEqualTo("[2,7,6,9,5,1,4,3,8]");
        assertThat(mapper.readValue("[2,7,6,9,5,1,4,3,8]", Grid.class)).isEqualTo(LO_SHU);

        StarSquareState mid = midGame();
        assertThat(mapper.readValue(mapper.writeValueAsString(mid), StarSquareState.class)).isEqualTo(mid);

        StarSquareEngine e = engine(3, Set.of(2), 3, 300);
        StarSquareState ended = e.desert(e.desert(mid, 0, mid.phaseStartedAt() + 1).newState(), 1,
                mid.phaseStartedAt() + 2).newState();
        assertThat(ended.ended()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(ended), StarSquareState.class)).isEqualTo(ended);
    }

    @Test
    void the_public_view_while_playing_has_no_solution_and_no_boards() throws Exception {
        StarSquareState mid = midGame();
        JsonNode view = mapper.valueToTree(StarSquareStateMapper.toPublicView(mid, 1234));
        List<String> keys = keys(view);
        assertThat(keys).doesNotContain("solution", "grid", "grids", "puzzles", "matchSeed", "pace", "order", "version");
        assertThat(view.get("clues").size()).isEqualTo(9);
        assertThat(view.get("seats").get(0).get("filled").asInt()).isEqualTo(4);
        // 정답 숫자(3·4·8·9 — 단서 2·1 밖)가 공개 뷰 어디에도 판 모양으로 실리지 않았다
        assertThat(view.toString()).doesNotContain("[2,7,6,9,5,1,4,3,8]");
    }

    @Test
    void events_carry_only_counts_while_playing_and_reveal_boards_after_the_puzzle() throws Exception {
        JsonNode started = mapper.valueToTree(new StarSquareEvent.PuzzleStarted(0, 3, 3, 15,
                StarSquareFixtures.LO_SHU_CLUES, 300_000, 2, 0));
        assertThat(fieldNames(started)).containsExactlyInAnyOrder("@event", "index", "count", "size", "magicSum",
                "clues", "limitMs", "filled", "lines");
        assertThat(started.get("@event").asText()).isEqualTo("PUZZLE_STARTED");
        JsonNode progress = mapper.valueToTree(new StarSquareEvent.Progress(1, 5, 2));
        assertThat(fieldNames(progress)).containsExactlyInAnyOrder("@event", "seat", "filled", "lines");
        assertThat(keys(progress)).doesNotContain("private");

        StarSquareEngine e = engine(1, Set.of(), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        var r = e.apply(s, 0, sync(0, 1, LO_SHU.toArray()), s.phaseStartedAt() + 10);
        JsonNode ended = mapper.valueToTree(r.events().get(1));
        assertThat(ended.get("@event").asText()).isEqualTo("PUZZLE_ENDED");
        assertThat(ended.get("solution").toString()).isEqualTo("[2,7,6,9,5,1,4,3,8]");
        assertThat(ended.get("grids").get(0).toString()).isEqualTo("[2,7,6,9,5,1,4,3,8]");
        for (StarSquareEvent ev : r.events()) {
            String json = mapper.writeValueAsString(ev);
            assertThat(mapper.readValue(json, StarSquareEvent.class)).isEqualTo(ev);
        }
    }

    @Test
    void the_private_view_is_my_board_only_and_hidden_during_the_intro() {
        StarSquareState mid = midGame();
        var mine = StarSquareStateMapper.toPrivateBoard(mid, 0, Set.of(2)).orElseThrow();
        assertThat(mine.grid()).isEqualTo(Grid.of(2, 7, 6, 0, 0, 1, 0, 0, 0));
        assertThat(mine.version()).isEqualTo(4);
        assertThat(StarSquareStateMapper.toPrivateBoard(mid, 2, Set.of(2))).isEmpty();
        assertThat(StarSquareStateMapper.toPrivateBoard(mid, 5, Set.of(2))).isEmpty();

        StarSquareEngine e = engine(2, Set.of(), 1, 300);
        StarSquareState intro = e.start(loShuPuzzles(1), 1L, StarSquareFixtures.T0).newState();
        assertThat(StarSquareStateMapper.toPrivateBoard(intro, 0, Set.of())).isEmpty();
        var introView = StarSquareStateMapper.toPublicView(intro, 3_000);
        assertThat(introView.clues()).isNull();
        assertThat(introView.size()).isZero();
        assertThat(introView.seats()).allMatch(seat -> seat.filled() == 0);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                out.add(name);
                collect(node.get(name), out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }
}
