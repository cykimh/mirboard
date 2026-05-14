package com.mirboard.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryMessageGatewayTest {

    @Test
    void publish_invokes_exact_channel_subscribers() {
        InMemoryMessageGateway gw = new InMemoryMessageGateway();
        List<String> received = new ArrayList<>();
        gw.subscribe("stomp:topic:room:r1", (c, p) -> received.add(c + "=" + p));

        gw.publish("stomp:topic:room:r1", "hello");

        assertThat(received).containsExactly("stomp:topic:room:r1=hello");
    }

    @Test
    void pattern_with_wildcard_matches_segment() {
        InMemoryMessageGateway gw = new InMemoryMessageGateway();
        List<String> received = new ArrayList<>();
        gw.subscribe("stomp:topic:room:*", (c, p) -> received.add(c));

        gw.publish("stomp:topic:room:abc", "x");
        gw.publish("stomp:topic:room:def", "y");
        gw.publish("stomp:topic:lobby:rooms", "z"); // 다른 prefix — 매치 안 함.

        assertThat(received).containsExactly("stomp:topic:room:abc", "stomp:topic:room:def");
    }

    @Test
    void publish_with_no_subscribers_is_noop() {
        InMemoryMessageGateway gw = new InMemoryMessageGateway();
        // 던지지 않으면 통과.
        gw.publish("ghost:channel", "nobody listens");
    }

    @Test
    void multiple_handlers_on_same_pattern_all_invoked() {
        InMemoryMessageGateway gw = new InMemoryMessageGateway();
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        gw.subscribe("ch", (c, p) -> a.add(p));
        gw.subscribe("ch", (c, p) -> b.add(p));

        gw.publish("ch", "x");

        assertThat(a).containsExactly("x");
        assertThat(b).containsExactly("x");
    }
}
