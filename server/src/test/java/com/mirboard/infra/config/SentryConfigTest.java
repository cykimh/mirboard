package com.mirboard.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * D-107 — Sentry 는 <b>DSN 이 없으면 존재하지 않는다</b>.
 *
 * <p>D-105 데모 계정과 같은 옵트인 패턴이다. "설정을 안 하면 조용히 아무것도 안 한다"가
 * 아니라 "설정을 안 하면 Bean 이 아예 없다" — 전자는 SDK 가 초기화되며 스레드·큐를 만들고
 * 실수로 네트워크를 건드릴 여지가 남지만, 후자는 그럴 코드가 실행조차 안 된다.
 *
 * <p>{@code ApplicationContextRunner} 를 쓰므로 Docker·풀 컨텍스트가 필요 없다.
 */
class SentryConfigTest {

    /** 문법만 유효한 가짜 DSN — 네트워크로 나가지 않는다(전송은 비동기 큐). */
    private static final String FAKE_DSN = "https://publickey@localhost/1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(SentryConfig.class);

    @Test
    @DisplayName("DSN 이 없으면 SentryConfig Bean 이 만들어지지 않는다")
    void absentWithoutDsn() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SentryConfig.class));
    }

    @Test
    @DisplayName("DSN 이 빈 문자열이어도 만들어지지 않는다 (환경변수 미치환 방어)")
    void absentWithBlankDsn() {
        // `SENTRY_DSN` 을 안 주면 application.yml 이 `dsn: ` 로 남아 빈 문자열이 온다.
        // @ConditionalOnProperty 는 빈 값을 '없음'으로 보지 않으므로 여기서 확인해 둔다.
        runner.withPropertyValues("mirboard.sentry.dsn=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SentryConfig.class));
    }

    @Test
    @DisplayName("DSN 이 있으면 Bean 이 생기고 루트 로거에 sentry 어펜더가 붙는다")
    void attachesAppenderWithDsn() {
        runner.withPropertyValues("mirboard.sentry.dsn=" + FAKE_DSN)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SentryConfig.class);
                    assertThat(rootAppenderNames()).contains("sentry");
                });
    }

    @Test
    @DisplayName("컨텍스트가 닫히면 어펜더를 떼어 낸다 (반복 기동 시 누적 방지)")
    void detachesOnShutdown() {
        runner.withPropertyValues("mirboard.sentry.dsn=" + FAKE_DSN)
                .run(ctx -> assertThat(rootAppenderNames()).contains("sentry"));

        // run(...) 이 끝나면 컨텍스트가 닫히고 @PreDestroy 가 돈다. 떼지 않으면 다음
        // 컨텍스트에서 어펜더가 두 개가 되어 이벤트가 중복 전송된다.
        assertThat(rootAppenderNames()).doesNotContain("sentry");
    }

    private static java.util.List<String> rootAppenderNames() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var names = new java.util.ArrayList<String>();
        root.iteratorForAppenders().forEachRemaining(a -> names.add(a.getName()));
        return names;
    }
}
