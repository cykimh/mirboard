package com.mirboard.infra.config;

import com.mirboard.domain.lobby.auth.AuthService;
import com.mirboard.domain.lobby.auth.UsernameTakenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 쇼케이스용 데모 계정 시더 (D-105, M4).
 *
 * <p><b>기본 꺼짐.</b> `mirboard.demo.enabled=true` 일 때만 동작한다 — 공개 비밀번호를 가진
 * 계정이 로컬·CI를 포함한 모든 환경에 자동으로 생기면 안 되기 때문이다. Flyway 마이그레이션
 * 대신 이 방식을 쓴 이유이기도 하다: 켜고 끄는 것이 설정 한 줄이고, 되돌리는 데 새
 * 마이그레이션이 필요 없다.
 *
 * <p>비밀번호 해시는 런타임에 {@link AuthService#register} 가 기존 `PasswordEncoder` 로
 * 만든다 — 해시를 소스에 박아두지 않는다.
 *
 * <p>이미 있으면 아무것도 하지 않는다(재기동 안전). 계정은 일반 사용자와 동일하게 취급되어
 * 레이트리밋·정지·모더레이션이 그대로 적용된다.
 *
 * <p><b>시딩 실패는 절대 기동을 막지 않는다.</b> 잘못된 설정값 하나로 서비스 전체가 뜨지
 * 못하는 것이 데모 계정이 없는 것보다 훨씬 나쁘다 — 실패는 경고 로그로만 남긴다.
 */
@Configuration
@ConditionalOnProperty(name = "mirboard.demo.enabled", havingValue = "true")
public class DemoAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

    @Bean
    ApplicationRunner seedDemoAccount(
            AuthService authService,
            @Value("${mirboard.demo.username:demo}") String username,
            @Value("${mirboard.demo.password:}") String password) {
        return args -> {
            if (password.isBlank()) {
                // 기본값을 두지 않는다 — 빈 값이면 조용히 약한 계정을 만드는 대신 거부한다.
                log.warn("데모 계정 시딩 건너뜀: mirboard.demo.password 가 비어 있다 "
                        + "(MIRBOARD_DEMO_PASSWORD 를 설정하라).");
                return;
            }
            try {
                var user = authService.register(username, password);
                log.info("데모 계정 생성: username={} userId={}", user.username(), user.userId());
            } catch (UsernameTakenException e) {
                log.info("데모 계정이 이미 있다: username={} (건너뜀)", username);
            } catch (Exception e) {
                // 데모 계정은 부가 기능이다 — 어떤 이유로든 실패해도 서버 기동을 막지 않는다.
                // ApplicationRunner 에서 예외가 새 나가면 Spring 이 컨텍스트를 닫고 프로세스가
                // 죽는다. 실제 경로가 있다: 정책 위반 값(UsernamePolicy `[A-Za-z0-9_]{3,20}`,
                // PasswordPolicy 8~64자)은 InvalidUsername/InvalidPasswordException 을 던지고,
                // 두 인스턴스가 동시에 최초 부팅하면 username 유니크 제약이 걸린다. 어느 쪽이든
                // 데모 계정 하나 때문에 서비스 전체가 크래시 루프에 빠지는 것이 훨씬 나쁘다.
                log.warn("데모 계정 시딩 실패 — 건너뜀 (서버는 정상 기동한다): {}", e.toString());
            }
        };
    }
}
