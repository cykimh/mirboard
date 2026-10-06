package com.mirboard.infra.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.mirboard.testsupport.LogCapture;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S5 — 데드라인 폴러의 주기 작업은 무엇이 던져도 멈추지 않는다.
 *
 * <p>{@code scheduleWithFixedDelay} 는 작업이 한 번이라도 던지면 이후 실행을 <b>조용히</b> 멈추고 예외는 아무도 읽지
 * 않는 {@code Future} 에 묻는다. 폴러는 {@code RuntimeException} 만 잡고 있어서 {@code Error}(스택 넘침·메모리 부족) 한
 * 번이면 그 인스턴스의 턴·엔진·탈주 데드라인이 전부 멈췄다 — 운영은 머신 1대라 서비스 전체의 경쟁 창이 안 닫힌다.
 */
class DeadlinePollerTest {

    private static final String KIND = "probe";

    private final DeadlineQueue queue = mock(DeadlineQueue.class);
    private LogCapture logs;

    @BeforeEach
    void captureLogs() {
        logs = LogCapture.of(DeadlinePoller.class);
    }

    @AfterEach
    void releaseLogs() {
        logs.close();
    }

    private static DeadlineHandler handler(Consumer<String> body) {
        return new DeadlineHandler() {
            @Override
            public String kind() {
                return KIND;
            }

            @Override
            public void handle(String member) {
                body.accept(member);
            }
        };
    }

    @Test
    void an_error_in_a_handler_does_not_stop_later_polls() throws Exception {
        when(queue.pollDue(KIND)).thenReturn(List.of("r1#1"));
        CountDownLatch calls = new CountDownLatch(3);
        AtomicBoolean first = new AtomicBoolean(true);
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> {
            calls.countDown();
            if (first.getAndSet(false)) {
                throw new StackOverflowError("simulated");
            }
        })), 10);

        poller.start();
        try {
            assertThat(calls.await(2, TimeUnit.SECONDS))
                    .as("Error 를 던진 다음 주기에도 폴링이 이어진다")
                    .isTrue();
        } finally {
            poller.shutdown();
        }
    }

    /** 이미 pop 한 항목은 이 인스턴스만 가진다 — 한 항목의 Error 로 나머지를 버리면 그 타이머들은 영영 사라진다. */
    @Test
    void an_error_from_one_member_does_not_drop_the_rest_of_the_popped_batch() {
        when(queue.pollDue(KIND)).thenReturn(List.of("r1#1", "r2#1"));
        List<String> handled = new CopyOnWriteArrayList<>();
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> {
            handled.add(member);
            if (member.equals("r1#1")) {
                throw new StackOverflowError("simulated");
            }
        })), 10);

        assertThatCode(poller::pollOnce).doesNotThrowAnyException();

        assertThat(handled).containsExactly("r1#1", "r2#1");
        assertThat(logs.events())
                .as("ERROR 로 스택과 함께 남긴다 — Sentry 는 ERROR 만 올린다")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("r1#1");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
    }

    @Test
    void an_error_while_popping_the_queue_is_logged_and_the_next_cycle_retries() {
        when(queue.pollDue(KIND)).thenThrow(new OutOfMemoryError("simulated"));
        DeadlinePoller poller = new DeadlinePoller(queue, List.of(handler(member -> { })), 10);

        assertThatCode(poller::pollOnce).doesNotThrowAnyException();
        assertThatCode(poller::pollOnce).doesNotThrowAnyException(); // 다음 주기에도 다시 pop 을 시도한다

        verify(queue, times(2)).pollDue(KIND);
        assertThat(logs.events())
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).contains("kind=" + KIND);
                    assertThat(event.getThrowableProxy()).as("스택이 함께 남는다").isNotNull();
                });
    }
}
