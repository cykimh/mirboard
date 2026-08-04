package com.mirboard.infra.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.sentry.Sentry;
import io.sentry.logback.SentryAppender;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/**
 * Sentry 오류 리포팅 (D-107, C3). <b>DSN 이 없으면 아무 일도 일어나지 않는다.</b>
 *
 * <p><b>공식 Spring Boot 스타터를 쓰지 않는다.</b> `sentry-spring-boot-starter-jakarta` 를
 * 붙여 컨텍스트를 띄워 보면 Sentry 자신이 경고를 낸다 —
 * {@code SentrySpringVersionChecker: !Incompatible Spring Boot Version detected!}. `-jakarta`
 * 변종은 Boot 3(Jakarta EE 10) 대상이고 이 프로젝트는 Boot 4(Spring Framework 7 /
 * Jakarta EE 11)다. 컴파일과 기동은 통과하므로 <b>실측하지 않으면 넘어갈 결함</b>이었다.
 *
 * <p><b>어펜더를 코드로 붙이는 이유.</b> 이 프로젝트는 `logback.xml` 없이 application.yml 의
 * {@code logging.pattern.console} 만 쓴다. `logback-spring.xml` 을 새로 넣으면 MDC 콘솔
 * 패턴을 두 곳에 적어야 해서 드리프트가 생긴다. 루트 로거에 어펜더만 추가하면 Spring Boot
 * 기본 로깅 구성은 손대지 않는다.
 *
 * <p><b>PII 는 보내지 않는다.</b> `users` 화이트리스트로 개인정보를 스키마에서 막아 놓고
 * 예외 리포트로 흘리면 원칙이 무의미하다({@code send-default-pii=false}). MDC 의
 * userId/roomId/eventId 는 태그로 붙는데, userId 는 내부 식별자이고 연락처가 아니다.
 */
@Configuration
// @ConditionalOnProperty 로는 안 된다 — 빈 문자열을 '있음'으로 본다. SENTRY_DSN 을
// 안 주면 application.yml 이 `dsn: ` 로 남아 빈 값이 오므로, Bean 이 생기고 어펜더까지
// 붙어 버린다(SentryConfigTest 가 이 함정을 잡았다).
@ConditionalOnExpression("'${mirboard.sentry.dsn:}' != ''")
public class SentryConfig {

    private static final Logger log = LoggerFactory.getLogger(SentryConfig.class);

    private final SentryAppender appender;

    public SentryConfig(@Value("${mirboard.sentry.dsn}") String dsn,
                        @Value("${mirboard.sentry.environment:local}") String environment,
                        @Value("${mirboard.sentry.release:}") String release,
                        @Value("${mirboard.sentry.traces-sample-rate:0.0}") double tracesSampleRate) {
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(environment);
            if (!release.isBlank()) {
                options.setRelease(release);
            }
            // 개인정보 최소화 원칙(CLAUDE.md) — IP·쿠키·헤더를 리포트에 싣지 않는다.
            options.setSendDefaultPii(false);
            // 기본 0 = 성능 트레이싱 끔. 스타터를 포기해 요청 트레이싱이 없으므로
            // 켜도 얻는 것이 적다 — 무료 쿼터를 오류 리포트에 쓰는 편이 낫다.
            options.setTracesSampleRate(tracesSampleRate);
        });

        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.appender = new SentryAppender();
        appender.setName("sentry");
        appender.setContext(context);
        // ERROR 만 이벤트로 올린다. WARN 까지 올리면 탈주 유예·레이트리밋 같은 정상
        // 운영 신호가 오류로 쌓여 쿼터를 태우고 신호 대 잡음이 나빠진다.
        appender.setMinimumEventLevel(Level.ERROR);
        // INFO 는 이벤트가 아니라 breadcrumb — 오류 직전 맥락으로만 붙는다.
        appender.setMinimumBreadcrumbLevel(Level.INFO);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);

        log.info("Sentry 활성화: environment={} release={} tracesSampleRate={}",
                environment, release.isBlank() ? "(미설정)" : release, tracesSampleRate);
    }

    @PreDestroy
    void shutdown() {
        // 큐에 남은 이벤트를 흘려보내고 어펜더를 떼어 낸다 — 테스트가 컨텍스트를 여러 번
        // 띄울 때 루트 로거에 어펜더가 누적되지 않게.
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        Sentry.close();
    }
}
