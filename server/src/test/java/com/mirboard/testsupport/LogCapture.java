package com.mirboard.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 테스트용 로그 캡처 — 한 클래스의 로거에 Logback {@link ListAppender} 를 붙여 <b>수준과 문장</b>을 단언한다.
 *
 * <p>D-130(원카드 S5)에서 들였다(프로젝트에 로그 단언 관례가 없었다). 콘솔 패턴은 Spring 컨텍스트가 떴는지에 따라 달라지므로
 * 출력 문자열 대신 이벤트를 본다 — "WARN 이던 것이 DEBUG 로", "ERROR 로(Sentry 는 ERROR 만 올린다)" 같은 수준
 * 변경을 정확히 고정하려는 것이다. 붙이는 동안 그 로거의 수준을 DEBUG 로 내리고 닫을 때 되돌린다.
 *
 * <pre>{@code
 * try (LogCapture logs = LogCapture.of(DeadlinePoller.class)) {
 *     ...
 *     assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("..."));
 * }
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    /** 지금까지 남은 이벤트(복사본). 다른 스레드(가상 스레드 루프 등)가 남긴 것도 들어온다. */
    public List<ILoggingEvent> events() {
        // AppenderBase.doAppend 는 appender 를 잠그고 추가한다 — 같은 잠금으로 읽는다.
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** 그 수준으로 남은 문장들(인자 치환 뒤). */
    public List<String> messages(Level level) {
        return events().stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
