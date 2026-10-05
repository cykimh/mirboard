package com.mirboard.infra.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirboard.domain.game.core.GameEvent;
import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.room.Room;
import com.mirboard.domain.lobby.room.RoomService;
import com.mirboard.domain.lobby.room.TeamPolicy;
import com.mirboard.infra.messaging.MessageGateway;
import com.mirboard.infra.messaging.StompPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * D-126 — 티츄 봇 매치 한 판의 STOMP 프레임을 전부 기록해 순번 흐름을 검증한다.
 *
 * <p>회귀 기준은 서버만 보고 판단할 수 있는 두 가지다 — 공개 토픽의 순번은 구멍 없이 이어지고,
 * 비공개 이벤트는 순번을 쓰지 않는다. 예전에는 카드를 낼 때마다 비공개 {@code HAND_DEALT} 가 방
 * 순번을 하나 써서, 다음 공개 이벤트가 모든 클라에서 구멍이 되고 4명이 전부 resync 했다.
 *
 * <p>보고용으로 클라 순번 판정({@code useStompRoom} + {@code tichuStore.applyEvent})을 좌석마다
 * 흉내 내 resync 횟수를 {@code build/d126-resync-stats.txt} 에 남긴다. 모델은 resync 응답이
 * 그 액션의 프레임이 다 나간 직후에 온다고 본다 — 실제로는 더 늦을 수 있어 수정 전 수치는 하한이다.
 */
@SpringBootTest
@Testcontainers
@Import(TichuEventStreamIT.RecordingConfig.class)
@TestPropertySource(properties = {
        "mirboard.jwt.secret=event-stream-test-secret-must-be-32-bytes-or-more",
        "mirboard.bot.seed=12345",
        "mirboard.bot.delay-millis=0"
})
class TichuEventStreamIT {

    /**
     * {@code tichuStore.applyEvent} 에 리듀서가 있는 공개 이벤트(D-126 기준). 나머지는 'unhandled'
     * 로 resync 를 부른다. 클라 리듀서가 바뀌면 같이 고칠 것.
     */
    private static final Set<String> CLIENT_HANDLED = Set.of(
            "PLAYED", "PASSED", "TURN_CHANGED", "TRICK_TAKEN", "PLAYER_FINISHED",
            "TICHU_DECLARED", "WISH_MADE", "WISH_CLEARED", "DRAGON_GIVEN", "PLAYER_READY",
            "PASSING_SUBMITTED", "ROUND_ENDED", "PLAYER_DISCONNECTED",
            "PLAYER_RECONNECTED", "CHIPS_SETTLED", "MATCH_ENDED");

    /**
     * 플레이 배치에서 resync 를 불러도 되는 유일한 이유 — 라운드를 끝낸 플레이 뒤의 새 라운드
     * 시작(8장 손패는 resync 의 비공개 뷰로 받는다).
     */
    private static final Set<String> ALLOWED_PLAY_BATCH_TRIGGERS = Set.of("unhandled:ROUND_STARTED");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired RoomService roomService;
    @Autowired BotUserRegistry bots;
    @Autowired FrameRecorder recorder;

    @Test
    void public_seq_is_gap_free_and_private_events_carry_no_seq() throws IOException {
        recorder.clear();
        long hostBotId = bots.getBotIds().get(0);
        Room room = roomService.createRoom(
                hostBotId, "event-stream", "TICHU", TeamPolicy.SEQUENTIAL, true);
        String roomId = room.roomId();
        String topic = "/topic/room/" + roomId;
        String queue = "/queue/room/" + roomId;

        // MATCH_ENDED 를 담은 브로드캐스트가 끝날 때까지 — 매치 완료 도메인 이벤트는 그 배치보다
        // 먼저 발행되므로 그것만 기다리면 마지막 프레임을 놓친다.
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recorder.matchEndedBatchClosed(roomId));

        List<Frame> frames = recorder.framesOf(roomId, topic, queue);
        long plays = frames.stream()
                .filter(f -> f.kind() == Kind.TOPIC && "PLAYED".equals(f.envelope().type()))
                .count();
        List<SeatTally> tallies = new ArrayList<>();
        for (long userId : room.playerIds()) {
            tallies.add(simulateClient(frames, userId, CLIENT_HANDLED));
        }
        writeReport(plays, tallies);

        List<Long> publicSeqs = frames.stream()
                .filter(f -> f.kind() == Kind.TOPIC && f.envelope().seq() != null)
                .map(f -> f.envelope().seq())
                .toList();
        assertThat(publicSeqs).isNotEmpty();
        for (int i = 1; i < publicSeqs.size(); i++) {
            assertThat(publicSeqs.get(i))
                    .as("공개 순번은 구멍 없이 이어져야 한다 (index %d)", i)
                    .isEqualTo(publicSeqs.get(i - 1) + 1);
        }
        assertThat(frames)
                .filteredOn(f -> f.kind() == Kind.USER)
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.envelope().seq())
                        .as("비공개 %s 는 순번을 쓰지 않는다", f.envelope().type())
                        .isNull());
        // 구멍 연쇄(미지 이벤트 뒤 같은 배치)는 클라 특성이라 빼고, 순번 자체가 만든 구멍만 본다.
        assertThat(tallies).allSatisfy(t -> {
            assertThat(t.rootGaps()).isZero();
            assertThat(t.playBatchTriggers().keySet())
                    .as("카드를 낸 배치의 resync 사유")
                    .isSubsetOf(ALLOWED_PLAY_BATCH_TRIGGERS);
        });
    }

    /**
     * 한 좌석 클라의 순번 판정 흉내. 공개 프레임만 판정하고(본인 큐는 {@code lastSeq} 를 올리지
     * 않는다), 구멍·미지 이벤트마다 resync 를 한 번씩 센다 — 훅이 프레임마다 resync 를 부른다.
     * 미지 이벤트도 {@code lastSeq} 를 올리지 않으므로 같은 배치의 뒤 프레임은 연쇄로 구멍이 된다.
     * 그것과 구분해, resync 대기 중이 아닐 때 난 구멍을 {@code rootGaps} 로 따로 센다.
     */
    private static SeatTally simulateClient(List<Frame> frames, long userId, Set<String> handled) {
        long lastSeq = 0;
        long issued = 0;
        boolean resyncPending = false;
        boolean batchHasPlay = false;
        int resyncs = 0;
        int resyncsInPlayBatches = 0;
        int resyncsThisBatch = 0;
        Map<String, Integer> batchTriggers = new TreeMap<>();
        Map<String, Integer> playBatchTriggers = new TreeMap<>();
        int gaps = 0;
        int rootGaps = 0;
        Map<String, Integer> triggers = new TreeMap<>();
        for (Frame f : frames) {
            if (f.kind() == Kind.BATCH_END) {
                if (resyncPending) {
                    lastSeq = issued;  // 스냅샷의 eventSeq — 그 시점까지 발급된 순번.
                    resyncPending = false;
                }
                if (batchHasPlay) {
                    resyncsInPlayBatches += resyncsThisBatch;
                    batchTriggers.forEach((k, v) -> playBatchTriggers.merge(k, v, Integer::sum));
                }
                batchHasPlay = false;
                resyncsThisBatch = 0;
                batchTriggers.clear();
                continue;
            }
            StompEnvelope<?> env = f.envelope();
            if (env.seq() != null) {
                issued = Math.max(issued, env.seq());
            }
            if (f.kind() == Kind.USER || env.seq() == null) {
                continue;  // 본인 큐·순번 없는 메타는 판정하지 않는다.
            }
            if ("PLAYED".equals(env.type())) {
                batchHasPlay = true;
            }
            long seq = env.seq();
            if (seq <= lastSeq) {
                continue;  // duplicate
            }
            String trigger;
            if (seq > lastSeq + 1) {
                gaps++;
                if (!resyncPending) {
                    rootGaps++;
                }
                trigger = "gap:" + env.type();
            } else if (!handled.contains(env.type())) {
                trigger = "unhandled:" + env.type();
            } else {
                lastSeq = seq;
                continue;
            }
            resyncs++;
            resyncsThisBatch++;
            resyncPending = true;
            triggers.merge(trigger, 1, Integer::sum);
            batchTriggers.merge(trigger, 1, Integer::sum);
        }
        return new SeatTally(userId, resyncs, resyncsInPlayBatches, gaps, rootGaps, triggers,
                playBatchTriggers);
    }

    private static void writeReport(long plays, List<SeatTally> tallies) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("plays=").append(plays).append('\n');
        for (SeatTally t : tallies) {
            sb.append(String.format(
                    "user=%d resyncs=%d inPlayBatches=%d perPlay=%.3f gaps=%d rootGaps=%d"
                            + " triggers=%s playBatchTriggers=%s%n",
                    t.userId(), t.resyncs(), t.resyncsInPlayBatches(),
                    plays == 0 ? 0.0 : (double) t.resyncsInPlayBatches() / plays,
                    t.gaps(), t.rootGaps(), t.triggers(), t.playBatchTriggers()));
        }
        Path out = Path.of("build", "d126-resync-stats.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
    }

    enum Kind { TOPIC, USER, BATCH_END }

    record Frame(Kind kind, String roomId, String destination, StompEnvelope<?> envelope) {
    }

    record SeatTally(long userId, int resyncs, int resyncsInPlayBatches, int gaps, int rootGaps,
                     Map<String, Integer> triggers, Map<String, Integer> playBatchTriggers) {
    }

    /** 발행 순서 그대로의 프레임 기록. 액션은 방 락으로 직렬화돼 있어 순서가 곧 발행 순서다. */
    static class FrameRecorder {
        private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();

        void record(Kind kind, String roomId, String destination, Object envelope) {
            if (kind == Kind.BATCH_END) {
                frames.add(new Frame(kind, roomId, destination, null));
            } else if (envelope instanceof StompEnvelope<?> env) {
                frames.add(new Frame(kind, roomId, destination, env));
            }
        }

        void clear() {
            frames.clear();
        }

        List<Frame> framesOf(String roomId, String topic, String queue) {
            return frames.stream()
                    .filter(f -> switch (f.kind()) {
                        case BATCH_END -> roomId.equals(f.roomId());
                        case TOPIC -> topic.equals(f.destination());
                        case USER -> queue.equals(f.destination());
                    })
                    .toList();
        }

        boolean matchEndedBatchClosed(String roomId) {
            boolean sawMatchEnded = false;
            for (Frame f : frames) {
                if (f.kind() == Kind.TOPIC
                        && ("/topic/room/" + roomId).equals(f.destination())
                        && "MATCH_ENDED".equals(f.envelope().type())) {
                    sawMatchEnded = true;
                } else if (sawMatchEnded && f.kind() == Kind.BATCH_END
                        && roomId.equals(f.roomId())) {
                    return true;
                }
            }
            return false;
        }
    }

    @TestConfiguration
    static class RecordingConfig {

        @Bean
        FrameRecorder frameRecorder() {
            return new FrameRecorder();
        }

        @Bean
        @Primary
        StompPublisher recordingStompPublisher(MessageGateway gateway, ObjectMapper objectMapper,
                                               FrameRecorder recorder) {
            return new StompPublisher(gateway, objectMapper) {
                @Override
                public void publishToTopic(String destination, Object envelope) {
                    recorder.record(Kind.TOPIC, null, destination, envelope);
                    super.publishToTopic(destination, envelope);
                }

                @Override
                public void publishToUser(long userId, String destination, Object envelope) {
                    recorder.record(Kind.USER, null, destination, envelope);
                    super.publishToUser(userId, destination, envelope);
                }
            };
        }

        @Bean
        @Primary
        GameEventBroadcaster recordingBroadcaster(StompPublisher publisher, RoomSeq seqs,
                                                  Clock clock, FrameRecorder recorder) {
            return new GameEventBroadcaster(publisher, seqs, clock) {
                @Override
                public void broadcast(String roomId, List<? extends GameEvent> events,
                                      List<Long> playerIds) {
                    super.broadcast(roomId, events, playerIds);
                    recorder.record(Kind.BATCH_END, roomId, null, null);
                }
            };
        }
    }
}
