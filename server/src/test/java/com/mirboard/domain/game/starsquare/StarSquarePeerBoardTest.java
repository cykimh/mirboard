package com.mirboard.domain.game.starsquare;

import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.LO_SHU_CLUES;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.engine;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.loShuPuzzles;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.playing;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.sync;
import static com.mirboard.domain.game.starsquare.StarSquareFixtures.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.starsquare.event.StarSquareEvent;
import com.mirboard.domain.game.starsquare.invariant.StarSquareInvariantChecker;
import com.mirboard.domain.game.starsquare.puzzle.Grid;
import com.mirboard.domain.game.starsquare.state.StarSquareState;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper;
import com.mirboard.domain.game.starsquare.state.StarSquareStateMapper.PeerView;
import com.mirboard.infra.messaging.StompPublisher;
import com.mirboard.infra.ws.GameEventBroadcaster;
import com.mirboard.infra.ws.RoomSeq;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 완성한 사람의 남의 판 보기(D-294, `docs/rules-starsquare.md` §11.2)의 은닉 — 남의 판은 <b>이 퍼즐을 완성한 좌석의 본인 큐로만</b> 간다.
 * 아직 푸는 좌석·봇·탈주한 좌석·관전자(좌석 없음)에게는 가지 않고, 공개 토픽·공개 뷰에는 실리지 않는다.
 */
class StarSquarePeerBoardTest {

    private static final Grid PARTIAL = Grid.of(2, 7, 6, 0, 0, 1, 0, 0, 0);
    private static final Grid OTHER = Grid.of(2, 0, 0, 0, 5, 1, 0, 0, 0);

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<StarSquareEvent.PeerBoard> peers(List<StarSquareEvent> events) {
        return events.stream().filter(StarSquareEvent.PeerBoard.class::isInstance)
                .map(StarSquareEvent.PeerBoard.class::cast).toList();
    }

    @Test
    void nobody_sees_another_board_while_everyone_is_still_solving() {
        StarSquareEngine e = engine(4, Set.of(3), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        var r = e.apply(s, 0, sync(0, 1, PARTIAL.toArray()), start + 1_000);
        assertThat(types(r.events())).containsExactly("PROGRESS");
        // 봇 이정표도 마찬가지 — 완성한 사람이 없으면 판은 어디로도 가지 않는다
        var bot = e.onTimer(r.newState(), start + r.newState().board(3).pace().milestoneAt(1));
        assertThat(bot.events()).isNotEmpty();
        assertThat(peers(bot.events())).isEmpty();
        for (int seat = -1; seat <= 4; seat++) {
            assertThat(r.newState().peerSeats(seat)).as("seat %d", seat).isEmpty();
        }
        for (int seat = 0; seat < 3; seat++) {
            assertThat(StarSquareStateMapper.toPrivateBoard(r.newState(), seat, Set.of(3)).orElseThrow().peers())
                    .as("seat %d", seat).isEmpty();
        }
    }

    @Test
    void only_the_seat_that_solved_gets_the_unsolved_boards_and_every_later_change() {
        StarSquareEngine e = engine(4, Set.of(3), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        // 봇의 첫 이정표 5초 전에 좌석 1 이 완성한다 — 마감(완성 + 15초) 안에 봇 이정표가 온다
        long botAt = s.phaseStartedAt() + s.board(3).pace().milestoneAt(1);
        long start = botAt - 5_000;
        s = e.apply(s, 0, sync(0, 1, PARTIAL.toArray()), start + 1_000).newState();

        // 좌석 1 완성 → 좌석 1 에게만 남은 판(사람 0·2, 봇 3)을 한 번
        var solved = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 2_000);
        assertThat(types(solved.events())).containsExactly("SOLVED", "CLOSING", "PEER_BOARD", "PEER_BOARD", "PEER_BOARD");
        assertThat(peers(solved.events())).containsExactly(
                new StarSquareEvent.PeerBoard(1, 0, 0, PARTIAL),
                new StarSquareEvent.PeerBoard(1, 0, 2, LO_SHU_CLUES),
                new StarSquareEvent.PeerBoard(1, 0, 3, solved.newState().board(3).grid()));
        s = solved.newState();
        StarSquareInvariantChecker.check(s, e);

        // 아직 푸는 좌석 2 의 판이 바뀜 → 완성한 좌석 1 에게만. 진행도가 그대로여도 판은 간다
        var change = e.apply(s, 2, sync(0, 1, OTHER.toArray()), start + 3_000);
        assertThat(peers(change.events())).containsExactly(new StarSquareEvent.PeerBoard(1, 0, 2, OTHER));
        var sameProgress = e.apply(change.newState(), 2, sync(0, 2, 2, 0, 0, 0, 4, 1, 0, 0, 0), start + 3_500);
        assertThat(types(sameProgress.events())).containsExactly("PEER_BOARD");
        s = sameProgress.newState();

        // 봇 이정표 → 완성한 사람에게만
        var bot = e.onTimer(s, botAt);
        assertThat(peers(bot.events())).isNotEmpty().allSatisfy(p -> {
            assertThat(p.viewer()).isEqualTo(1);
            assertThat(p.seat()).isEqualTo(3);
        });

        // 모든 PEER_BOARD 는 비공개·순번 없음, 받는 좌석은 완성한 좌석 1 뿐. 그 밖의 이벤트는 공개·순번 있음
        for (var r : List.of(solved, change, sameProgress, bot)) {
            for (StarSquareEvent ev : r.events()) {
                if (ev instanceof StarSquareEvent.PeerBoard p) {
                    assertThat(p.isPrivate()).isTrue();
                    assertThat(p.sequenced()).isFalse();
                    assertThat(p.privateSeat()).isEqualTo(1);
                } else {
                    assertThat(ev.isPrivate()).isFalse();
                    assertThat(ev.sequenced()).isTrue();
                }
            }
        }
    }

    @Test
    void the_private_view_carries_peers_only_for_a_seat_that_solved() throws Exception {
        StarSquareEngine e = engine(4, Set.of(3), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        s = e.apply(s, 0, sync(0, 1, PARTIAL.toArray()), start + 1_000).newState();
        s = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 2_000).newState();

        var mine = StarSquareStateMapper.toPrivateBoard(s, 1, Set.of(3)).orElseThrow();
        assertThat(mine.solved()).isTrue();
        assertThat(mine.peers()).extracting(PeerView::seat).containsExactly(0, 2, 3);
        assertThat(mine.peers().get(0).grid()).isEqualTo(PARTIAL);
        // 아직 푸는 좌석 — 남의 판 없음(완성한 좌석 1 의 판도 없다)
        for (int seat : List.of(0, 2)) {
            var view = StarSquareStateMapper.toPrivateBoard(s, seat, Set.of(3)).orElseThrow();
            assertThat(view.peers()).as("seat %d", seat).isEmpty();
            assertThat(mapper.writeValueAsString(view)).doesNotContain("[2,7,6,9,5,1,4,3,8]");
        }
        // 봇·관전자(좌석 없음)는 비공개 뷰 자체가 없다
        assertThat(StarSquareStateMapper.toPrivateBoard(s, 3, Set.of(3))).isEmpty();
        assertThat(StarSquareStateMapper.toPrivateBoard(s, -1, Set.of(3))).isEmpty();

        // 공개 뷰에는 여전히 판이 없다 — 완성한 사람이 있어도
        JsonNode view = mapper.valueToTree(StarSquareStateMapper.toPublicView(s, 1234));
        assertThat(view.toString()).doesNotContain("peers", "\"grid\"", "grids", "solution")
                .doesNotContain("[2,7,6,0,0,1,0,0,0]").doesNotContain("[2,7,6,9,5,1,4,3,8]");
    }

    @Test
    void a_solver_who_left_stops_receiving_and_a_leaver_is_no_longer_shown() {
        StarSquareEngine e = engine(4, Set.of(), 1, 300);
        StarSquareState s = playing(e, loShuPuzzles(1));
        long start = s.phaseStartedAt();
        s = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 2_000).newState();
        s = e.apply(s, 3, sync(0, 1, LO_SHU.toArray()), start + 2_500).newState();
        // 완성한 좌석 1 이 나감 → 더 받지 않는다. 남은 완성 좌석 3 만 받는다
        s = e.desert(s, 1, start + 3_000).newState();
        var change = e.apply(s, 0, sync(0, 1, PARTIAL.toArray()), start + 3_500);
        assertThat(peers(change.events())).containsExactly(new StarSquareEvent.PeerBoard(3, 0, 0, PARTIAL));
        assertThat(change.newState().peerSeats(1)).isEmpty();
        assertThat(StarSquareStateMapper.toPrivateBoard(change.newState(), 1, Set.of()).orElseThrow().peers()).isEmpty();
        // 아직 푸는 좌석 2 가 나감 → 좌석 3 의 볼 목록에서 빠진다
        s = e.desert(change.newState(), 2, start + 4_000).newState();
        assertThat(s.peerSeats(3)).containsExactly(0);
    }

    @Test
    void the_last_solve_ends_the_puzzle_without_peer_boards() {
        StarSquareEngine e = engine(2, Set.of(), 2, 300);
        StarSquareState s = playing(e, loShuPuzzles(2));
        long start = s.phaseStartedAt();
        s = e.apply(s, 0, sync(0, 1, LO_SHU.toArray()), start + 1_000).newState();
        var last = e.apply(s, 1, sync(0, 1, LO_SHU.toArray()), start + 2_000);
        assertThat(types(last.events())).containsExactly("SOLVED", "PUZZLE_ENDED");
        assertThat(StarSquareStateMapper.toPrivateBoard(last.newState(), 0, Set.of()).orElseThrow().peers()).isEmpty();
    }

    @Test
    void the_broadcaster_sends_a_peer_board_to_the_viewers_own_queue_only_and_takes_no_sequence() throws Exception {
        StompPublisher publisher = mock(StompPublisher.class);
        RoomSeq seqs = mock(RoomSeq.class);
        GameEventBroadcaster broadcaster = new GameEventBroadcaster(publisher, seqs, Clock.systemUTC());
        var event = new StarSquareEvent.PeerBoard(1, 0, 2, PARTIAL);
        broadcaster.broadcast("r1", List.of(event), List.of(10L, 11L, 12L));

        verify(publisher, times(1)).publishToUser(eq(11L), eq("/queue/room/r1"), any());
        verify(publisher, never()).publishToTopic(anyString(), any());
        verify(seqs, never()).next(anyString());

        JsonNode json = mapper.valueToTree(event);
        assertThat(json.get("@event").asText()).isEqualTo("PEER_BOARD");
        assertThat(json.get("grid").toString()).isEqualTo("[2,7,6,0,0,1,0,0,0]");
        assertThat(mapper.readValue(mapper.writeValueAsString(event), StarSquareEvent.class)).isEqualTo(event);
    }
}
