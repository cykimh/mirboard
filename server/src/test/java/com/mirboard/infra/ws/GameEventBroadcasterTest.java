package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.infra.messaging.StompPublisher;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-126 — 순번을 쓰지 않는 이벤트({@link GameEvent#sequenced()} false)는 방 순번을 발급받지 않는다.
 * 비공개 이벤트가 공개 순번을 쓰면 그 이벤트를 받지 않는 클라에게 다음 공개 이벤트가 구멍이 된다.
 */
class GameEventBroadcasterTest {

    private static final String ROOM = "r1";
    private static final List<Long> PLAYERS = List.of(11L, 12L, 13L, 14L);

    private final StompPublisher publisher = mock(StompPublisher.class);
    private final RoomSeq seqs = mock(RoomSeq.class);
    private final GameEventBroadcaster broadcaster =
            new GameEventBroadcaster(publisher, seqs, Clock.systemUTC());

    /** 공개(좌석 -1) 또는 비공개 이벤트. 순번 사용 여부는 인자로 정한다. */
    private record Ev(String type, int seat, boolean seqd) implements GameEvent {
        @Override
        public String envelopeType() {
            return type;
        }

        @Override
        public int privateSeat() {
            return seat;
        }

        @Override
        public boolean sequenced() {
            return seqd;
        }
    }

    @Test
    void sequenced_is_the_port_default() {
        GameEvent plain = () -> "PLAIN";
        assertThat(plain.sequenced()).isTrue();
    }

    @Test
    void unsequenced_event_takes_no_room_seq_and_carries_none() {
        when(seqs.next(ROOM)).thenReturn(7L, 8L);

        broadcaster.broadcast(ROOM, List.of(
                new Ev("PLAYED", -1, true),
                new Ev("HAND_DEALT", 2, false),
                new Ev("TURN_CHANGED", -1, true)), PLAYERS);

        verify(seqs, times(2)).next(ROOM);
        ArgumentCaptor<Object> topic = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publishToTopic(eq("/topic/room/" + ROOM), topic.capture());
        assertThat(topic.getAllValues())
                .extracting(e -> ((StompEnvelope<?>) e).seq())
                .containsExactly(7L, 8L);
        ArgumentCaptor<Object> queue = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishToUser(eq(13L), eq("/queue/room/" + ROOM), queue.capture());
        assertThat(((StompEnvelope<?>) queue.getValue()).seq()).isNull();
    }

    @Test
    void private_event_routing_is_unchanged_for_out_of_range_seat() {
        broadcaster.broadcast(ROOM, List.of(new Ev("HAND_DEALT", 9, false)), PLAYERS);

        verify(publisher, times(0)).publishToUser(anyLong(), any(), any());
        verify(publisher, times(0)).publishToTopic(any(), any());
    }
}
